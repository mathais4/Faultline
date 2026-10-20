package io.faultline.lab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import io.faultline.core.Json;
import io.faultline.fault.FaultSpec.Phase;
import io.faultline.lab.Experiment.FaultPlan;
import io.faultline.lab.Experiment.Slo;
import io.faultline.lab.Experiment.Variant;
import io.faultline.queue.InMemoryJobQueue;
import io.faultline.queue.QueueConfig;
import io.faultline.resilience.ResiliencePolicy;
import io.faultline.resilience.RetryStrategy;
import io.faultline.service.Cluster;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Starts real services on real sockets and checks that experiments measure what they claim to. */
public class LabIntegrationTest {
    private static Cluster cluster;
    private static Runner runner;

    @BeforeClass
    public static void start() throws Exception {
        InMemoryJobQueue queue = new InMemoryJobQueue(); // shared: the services consume it, the runner audits it
        cluster = Cluster.start(queue);
        runner = new Runner(cluster.endpoints(), queue, s -> { });
    }

    @AfterClass
    public static void stop() {
        cluster.close();
    }

    private static ResiliencePolicy retry(boolean idem) {
        return new ResiliencePolicy(400, 3, RetryStrategy.EXPONENTIAL_JITTER, 20, 200, 3000, null, null, idem);
    }

    @SuppressWarnings("unchecked")
    private static double metric(Map<String, Object> exp, int variant, String key) {
        Map<String, Object> v = (Map<String, Object>) Json.list(exp, "variants").get(variant);
        return Json.dbl(Json.map(Json.map(v, "summary"), key), "mean", Double.NaN);
    }

    @Test
    public void retriesWithoutIdempotencyKeysDoubleChargeAndKeysPreventIt() {
        Experiment e = new Experiment("t-lost", "t", "q", "d", 150, 100, 11, Map.of(),
                List.of(FaultPlan.drop("payments", "drop-after", "/charge", 0.3, Phase.AFTER)),
                List.of(new Variant("plain", "plain", "", retry(false), QueueConfig.DEFAULT),
                        new Variant("idem", "idem", "", retry(true), QueueConfig.DEFAULT)),
                new Slo(0.9, -1, 0, -1, -1, -1, -1, -1), 3000);
        Map<String, Object> r = runner.runExperiment(e, 1, 11);

        assertTrue("blind retries must double-charge someone", metric(r, 0, "doubleCharged") > 0);
        assertEquals("idempotency keys must prevent every double charge", 0, metric(r, 1, "doubleCharged"), 0);
        assertTrue(metric(r, 1, "successRate") > 0.9);
        assertEquals("failed", verdict(r, 0));
        assertEquals("worked", verdict(r, 1));
    }

    @Test
    public void noRetryLeavesChargedCustomersWhoSawAnError() {
        Experiment e = new Experiment("t-none", "t", "q", "d", 150, 100, 12, Map.of(),
                List.of(FaultPlan.drop("payments", "drop-after", "/charge", 0.3, Phase.AFTER)),
                List.of(new Variant("none", "none", "", ResiliencePolicy.NAIVE, QueueConfig.DEFAULT)),
                Slo.NONE, 3000);
        Map<String, Object> r = runner.runExperiment(e, 1, 12);
        assertTrue(metric(r, 0, "chargedNotAccepted") > 20); // ~30% of 150
        assertEquals(0, metric(r, 0, "doubleCharged"), 0);
    }

    @Test
    public void lostAcksCauseDoubleShipmentsUnlessConsumerIsIdempotent() {
        QueueConfig plain = new QueueConfig(300, 5, RetryStrategy.NONE, 0, 0, 4, true, false);
        QueueConfig idem = new QueueConfig(300, 5, RetryStrategy.NONE, 0, 0, 4, true, true);
        Experiment e = new Experiment("t-queue", "t", "q", "d", 100, 100, 13, Map.of(),
                List.of(FaultPlan.lostAck("worker", "ack-lost", 0.4)),
                List.of(new Variant("plain", "plain", "", ResiliencePolicy.NAIVE, plain),
                        new Variant("idem", "idem", "", ResiliencePolicy.NAIVE, idem)),
                new Slo(-1, -1, -1, 0, 0, 0, -1, 10000), 10000);
        Map<String, Object> r = runner.runExperiment(e, 1, 13);
        assertTrue("redelivery of un-acked jobs must double-ship", metric(r, 0, "doubleShipped") > 0);
        assertEquals(0, metric(r, 1, "doubleShipped"), 0);
        assertEquals("no order may be lost", 0, metric(r, 1, "lostOrders"), 0);
        assertEquals(0, metric(r, 1, "pendingAfterDrain"), 0);
    }

    @Test
    public void workerCrashStrandsInFlightJobsUntilVisibilityTimeoutExpires() {
        QueueConfig shortVis = new QueueConfig(300, 5, RetryStrategy.NONE, 0, 0, 4, true, true);
        QueueConfig longVis = new QueueConfig(3000, 5, RetryStrategy.NONE, 0, 0, 4, true, true);
        // Jobs take 150ms at 20/s, so several are in flight when the worker tier dies at t+1.0s (back at t+1.8s).
        Experiment e = new Experiment("t-crash", "t", "q", "d", 60, 20, 15, Map.of(),
                List.of(FaultPlan.latency("worker", "job-duration", "process", 1.0, 150, Phase.BEFORE, 0, 0),
                        FaultPlan.crash("worker", "down", 1000, 1800)),
                List.of(new Variant("short", "short", "", ResiliencePolicy.NAIVE, shortVis),
                        new Variant("long", "long", "", ResiliencePolicy.NAIVE, longVis)),
                new Slo(-1, -1, -1, 0, 0, 0, -1, 15000), 15000);
        Map<String, Object> r = runner.runExperiment(e, 1, 15);

        // the report shows the shipping-delay chart for experiments that judge recovery or drain, with real data in it
        Map<String, Object> saved = Json.parseObject(Json.write(Map.of("generatedAt", "now", "queueBackend", "in-memory",
                "trials", 1, "experiments", List.of(r))));
        String html = ReportWriter.html(saved);
        assertTrue(html.contains("Time to ship, by when the order arrived"));
        assertFalse(html.contains("NaN"));

        assertEquals("nothing may be lost: stranded jobs are redelivered", 0, metric(r, 0, "lostOrders"), 0);
        assertEquals(0, metric(r, 1, "lostOrders"), 0);
        assertEquals(0, metric(r, 1, "pendingAfterDrain"), 0);
        assertTrue("a crash must leave redeliveries behind", metric(r, 1, "redeliveries") > 0);
        assertTrue("stranded jobs wait out a long visibility timeout (lag max " + metric(r, 1, "shipLagMax") + ")",
                metric(r, 1, "shipLagMax") > 2500);
        assertTrue("short timeout recovers sooner", metric(r, 0, "shipLagMax") < 2000);
        assertTrue("long timeout recovers later: " + metric(r, 0, "recoveryMs") + " vs " + metric(r, 1, "recoveryMs"),
                metric(r, 1, "recoveryMs") > metric(r, 0, "recoveryMs") + 1000);
    }

    @Test
    public void everyVariantSeesTheSamePlannedFaults() {
        Experiment e = Scenarios.byId("lost-response");
        assertEquals(Runner.fingerprint(e, 5), Runner.fingerprint(e, 5));
        assertNotEquals(Runner.fingerprint(e, 5), Runner.fingerprint(e, 6));
    }

    @Test
    public void reportRendersFromSavedResults() {
        Experiment e = new Experiment("t-report", "Report test", "q?", "d", 60, 100, 14, Map.of(),
                List.of(FaultPlan.error("payments", "e", "/charge", 0.3, 503)),
                List.of(new Variant("none", "none", "", ResiliencePolicy.NAIVE, QueueConfig.DEFAULT),
                        new Variant("retry", "retry", "", retry(false), QueueConfig.DEFAULT)),
                new Slo(0.9, -1, -1, -1, -1, -1, -1, -1), 3000);
        Map<String, Object> run = Map.of("generatedAt", "now", "queueBackend", "in-memory", "trials", 1,
                "experiments", List.of(runner.runExperiment(e, 1, 14)));
        Map<String, Object> saved = Json.parseObject(Json.write(run)); // exactly what `faultline report` does
        String html = ReportWriter.html(saved), md = ReportWriter.markdown(saved);
        assertTrue(html.contains("Report test") && html.contains("<svg"));
        assertFalse(html.contains("NaN") || md.contains("NaN"));
        assertTrue(md.contains("| none |"));
    }

    @SuppressWarnings("unchecked")
    private static String verdict(Map<String, Object> exp, int variant) {
        return Json.str((Map<String, Object>) Json.list(exp, "variants").get(variant), "verdict", "");
    }
}

package io.faultline.service;

import io.faultline.core.Hashing;
import io.faultline.core.Json;
import io.faultline.fault.FaultInjector;
import io.faultline.queue.Job;
import io.faultline.queue.JobQueue;
import io.faultline.queue.QueueConfig;
import io.faultline.resilience.CallResult;
import io.faultline.resilience.ResiliencePolicy;
import io.faultline.resilience.ResilientClient;
import io.faultline.resilience.RetryStrategy;
import io.faultline.trace.Span;
import io.faultline.trace.TraceCtx;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes fulfilment jobs from the queue and ships them via inventory /commit.
 *
 * Fault routes understood here (use them in a FaultSpec):
 * <ul>
 *   <li>"process" LATENCY BEFORE: slow worker. If it outlasts the visibility timeout the job is redelivered while
 *       the first worker is still running it.</li>
 *   <li>"process" ERROR: job fails before doing work, worker nacks.</li>
 *   <li>"process" DROP BEFORE / AFTER: worker dies before / after doing the work, never acks or nacks.</li>
 *   <li>"ack" LOST_ACK: work done, acknowledgement never reaches the queue.</li>
 *   <li>CRASH: worker loops stop claiming for the crash window; an in-flight job is abandoned un-acked.</li>
 * </ul>
 * The worker's own HTTP listener only serves the control API; its real work happens in background loops.
 */
public final class WorkerService extends ServiceNode {
    private final JobQueue queue;
    private final String inventoryUrl;
    private volatile QueueConfig qc = QueueConfig.DEFAULT;
    private volatile ResilientClient commitClient;
    private volatile int generation;
    private final java.util.concurrent.atomic.AtomicInteger activeJobs = new java.util.concurrent.atomic.AtomicInteger();

    public WorkerService(int port, String inventoryUrl, JobQueue queue) {
        super("worker", port);
        this.inventoryUrl = inventoryUrl;
        this.queue = queue;
    }

    @Override
    protected Response handle(Request req) {
        return Response.error(404, "worker has no business routes");
    }

    @Override
    protected void onConfigure(Map<String, Object> cfg) {
        if (!cfg.containsKey("queue")) return;
        qc = QueueConfig.fromJson(Json.map(cfg, "queue"));
        ResiliencePolicy p = new ResiliencePolicy(800, 1, RetryStrategy.NONE, 0, 0, 2000, null, null,
                qc.consumerIdempotent());
        commitClient = new ResilientClient(name, "inventory", inventoryUrl, p, spans, metrics, seed);
        int gen = ++generation;
        for (int i = 0; i < qc.workers(); i++) {
            String workerId = "w" + (i + 1);
            Thread.ofVirtual().name("faultline-" + workerId).start(() -> loop(workerId, gen));
        }
    }

    @Override
    protected void onReset() {
        generation++; // stops any running loops
    }

    private void loop(String workerId, int gen) {
        while (gen == generation) {
            if (!isListening()) { // "crashed"
                sleep(20);
                continue;
            }
            List<Job> jobs;
            try {
                jobs = queue.claim(workerId, qc.visibilityMs(), 1);
            } catch (RuntimeException e) {
                metrics.inc("queue_errors");
                sleep(100);
                continue;
            }
            if (jobs.isEmpty()) {
                sleep(15);
                continue;
            }
            for (Job j : jobs) {
                try {
                    process(j, gen);
                } catch (RuntimeException e) {
                    metrics.inc("queue_errors"); // queue unreachable mid-job: the visibility timeout will redeliver
                }
            }
        }
    }

    @Override
    protected Map<String, Object> extraMetrics() {
        return Map.of("activeJobs", activeJobs.get());
    }

    private void process(Job job, int gen) {
        activeJobs.incrementAndGet();
        try {
            doProcess(job, gen);
        } finally {
            activeJobs.decrementAndGet();
        }
    }

    private void doProcess(Job job, int gen) {
        long startMs = System.currentTimeMillis();
        long t0 = System.nanoTime();
        Map<String, Object> payload = Json.parseObject(job.payload());
        String orderId = job.orderKey();
        String traceId = Json.str(payload, "traceId", TraceCtx.traceIdFor(orderId));
        String parent = Json.str(payload, "parentSpanId", "");
        String spanId = spans.newSpanId();
        TraceCtx ctx = new TraceCtx(traceId, spanId, orderId);

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("attempt", Integer.toString(job.attempts()));
        tags.put("jobId", Long.toString(job.id()));
        String status = "ok";
        metrics.inc("jobs_started");
        try {
            FaultInjector.Decision d = faults.decide("process", orderId, job.attempts(), startMs);
            if (!d.applied().isEmpty()) {
                tags.put("faults", String.join(",", d.applied()));
                for (String id : d.applied()) metrics.inc("fault." + id);
            }
            sleep(d.beforeDelayMs());
            if (!isListening() || gen != generation) {
                // The process "died" while this job was in flight: the work never happened and nothing is acked,
                // so the job only comes back when its visibility timeout expires.
                status = "abandoned_crash";
                metrics.inc("crash_abandoned");
                return;
            }
            if (d.dropBefore()) {
                status = "worker_died";
                metrics.inc("worker_died");
                return;
            }
            if (d.errorStatus() > 0) {
                status = "failed";
                nack(job, "injected process error", tags);
                return;
            }

            CallResult r = commitClient.post("/commit", Json.write(Map.of("orderId", orderId)), ctx, job.attempts());
            sleep(d.afterDelayMs());
            if (d.dropAfter()) {
                status = "worker_died_after_work";
                metrics.inc("worker_died");
                return;
            }
            if (!r.ok()) {
                status = "failed";
                nack(job, "commit failed: " + r.failure(), tags);
                return;
            }

            FaultInjector.Decision ad = faults.decide("ack", orderId, job.attempts(), System.currentTimeMillis());
            if (ad.lostAck()) {
                status = "ack_lost";
                tags.put("faults", ad.applied().toString());
                metrics.inc("ack_lost");
                for (String id : ad.applied()) metrics.inc("fault." + id);
                return;
            }
            if (!isListening() || gen != generation) {
                status = "abandoned_crash";
                return;
            }
            if (queue.ack(job)) {
                metrics.inc("jobs_acked");
            } else {
                status = "stale_ack";
                metrics.inc("stale_ack_rejected"); // fencing token no longer current: someone else owns the job now
            }
        } finally {
            spans.add(new Span(traceId, spanId, parent, name, "process job", startMs, (System.nanoTime() - t0) / 1e6,
                    status, tags));
        }
    }

    private void nack(Job job, String error, Map<String, String> tags) {
        QueueConfig c = qc;
        long delay = c.backoff().delayMs(job.attempts(), c.backoffBaseMs(), c.backoffMaxMs(),
                Hashing.unit(seed, "job-backoff", job.orderKey(), Integer.toString(job.attempts())));
        tags.put("error", error);
        metrics.inc("jobs_nacked");
        queue.nack(job, delay, error);
    }
}

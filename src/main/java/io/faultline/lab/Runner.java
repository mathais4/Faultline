package io.faultline.lab;

import io.faultline.core.Json;
import io.faultline.core.Stats;
import io.faultline.fault.FaultInjector;
import io.faultline.fault.FaultSpec;
import io.faultline.lab.Experiment.FaultPlan;
import io.faultline.lab.Experiment.Slo;
import io.faultline.lab.Experiment.Variant;
import io.faultline.queue.JobQueue;
import io.faultline.queue.QueueStats;
import io.faultline.service.Cluster.Endpoints;
import io.faultline.trace.Span;
import io.faultline.trace.TraceCtx;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs experiments against a set of services over HTTP and judges them against ground truth.
 *
 * "Ground truth" means the payments and inventory ledgers and the queue's own table, not what the client was told:
 * a customer who got an error but was charged, or who got "accepted" but was never shipped, only shows up there.
 */
public final class Runner {

    private static final long BUCKET_MS = 250;
    /** A shipment later than this after the customer was told "accepted" counts as delayed. */
    private static final long DELAYED_SHIP_MS = 1000;
    private static final long LEAD_MS = 800;
    private static final long CLIENT_TIMEOUT_MS = 6000;

    private final Endpoints ep;
    private final JobQueue queue;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    public Runner(Endpoints ep, JobQueue queue, Consumer<String> log) {
        this.ep = ep;
        this.queue = queue;
        this.log = log;
    }

    // ------------------------------------------------------------------------------------------------ public API

    public Map<String, Object> run(List<Experiment> experiments, int trials, long seedOverride, String backend) {
        warmup();
        List<Object> results = new ArrayList<>();
        for (Experiment e : experiments) {
            long seed = seedOverride != 0 ? seedOverride : e.seed();
            results.add(runExperiment(e, trials, seed));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("faultline", "1.0.0");
        out.put("generatedAt", java.time.Instant.now().toString());
        out.put("queueBackend", backend);
        out.put("java", System.getProperty("java.version"));
        out.put("cpus", Runtime.getRuntime().availableProcessors());
        out.put("trials", trials);
        out.put("experiments", results);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ experiment

    Map<String, Object> runExperiment(Experiment e, int trials, long seed) {
        log.accept("== " + e.id() + ": " + e.title());
        List<Object> variants = new ArrayList<>();
        for (Variant v : e.variants()) {
            List<Trial> ts = new ArrayList<>();
            for (int t = 0; t < trials; t++) {
                Trial tr = runTrial(e, v, t, seed + t);
                ts.add(tr);
                log.accept(String.format("   %-22s trial %d  success %5.1f%%  p99 %6.0fms  dblCharge %d  dblShip %d  lost %d  drain %.0fms",
                        v.id(), t + 1, tr.m.get("successRate") * 100, tr.m.get("p99"), tr.m.get("doubleCharged").intValue(),
                        tr.m.get("doubleShipped").intValue(), tr.m.get("lostOrders").intValue(), tr.m.get("drainMs")));
            }
            variants.add(variantJson(e, v, ts));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("title", e.title());
        out.put("question", e.question());
        out.put("description", e.description());
        out.put("orders", e.orders());
        out.put("ratePerSec", e.ratePerSec());
        out.put("loadDurationMs", e.loadDurationMs());
        out.put("seed", seed);
        out.put("trials", trials);
        out.put("faults", faultsJson(e));
        out.put("faultWindow", e.windowEndMs() > 0 ? Map.of("startMs", e.windowStartMs(), "endMs", e.windowEndMs()) : null);
        out.put("slo", sloJson(e.slo()));
        out.put("plannedFaultFingerprint", fingerprint(e, seed));
        out.put("variants", variants);
        return out;
    }

    private Map<String, Object> variantJson(Experiment e, Variant v, List<Trial> ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("label", v.label());
        m.put("description", v.description());
        m.put("policy", v.policy());
        m.put("policyText", v.policy().describe());
        m.put("queue", v.queue());
        m.put("queueText", v.queue().describe());

        int passed = 0;
        for (Trial t : ts) if (t.sloFailures.isEmpty()) passed++;
        boolean hasSlo = !e.slo().equals(Slo.NONE);
        m.put("verdict", !hasSlo ? "n/a" : passed == ts.size() ? "worked" : passed > 0 ? "partial" : "failed");
        m.put("trialsPassed", passed);
        Set<String> reasons = new java.util.LinkedHashSet<>();
        for (Trial t : ts) reasons.addAll(t.sloFailures);
        m.put("failedChecks", reasons);

        Map<String, Object> summary = new LinkedHashMap<>();
        for (String k : ts.get(0).m.keySet()) {
            double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            int n = 0;
            for (Trial t : ts) {
                double x = t.m.get(k);
                if (Double.isNaN(x)) continue;
                sum += x;
                min = Math.min(min, x);
                max = Math.max(max, x);
                n++;
            }
            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("mean", n == 0 ? null : round4(sum / n));
            agg.put("min", n == 0 ? null : round4(min));
            agg.put("max", n == 0 ? null : round4(max));
            agg.put("n", n);
            summary.put(k, agg);
        }
        m.put("summary", summary);
        List<Object> trials = new ArrayList<>();
        for (Trial t : ts) trials.add(t.toJson());
        m.put("trials", trials);
        m.put("sampleTraces", ts.get(0).sampleTraces);
        return m;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static List<Object> faultsJson(Experiment e) {
        List<Object> out = new ArrayList<>();
        for (FaultPlan f : e.faults()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", f.service());
            m.put("id", f.id());
            m.put("type", f.type());
            m.put("route", f.route());
            m.put("probability", f.probability());
            m.put("fromOffsetMs", f.fromOffsetMs());
            m.put("untilOffsetMs", f.untilOffsetMs());
            m.put("text", f.describe());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> sloJson(Slo s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minSuccessRate", s.minSuccessRate());
        m.put("maxP99Ms", s.maxP99Ms());
        m.put("maxDoubleCharged", s.maxDoubleCharged());
        m.put("maxDoubleShipped", s.maxDoubleShipped());
        m.put("maxLostOrders", s.maxLostOrders());
        m.put("maxDeadJobs", s.maxDeadJobs());
        m.put("maxRecoveryMs", s.maxRecoveryMs());
        m.put("maxDrainMs", s.maxDrainMs());
        return m;
    }

    /**
     * Hash of which fault fires on which order, computed from the seed alone (first attempt, every route). Two runs
     * with the same fingerprint injected the same faults into the same orders; the fingerprint is the same for every
     * variant of an experiment, which is what makes the comparison fair.
     */
    static String fingerprint(Experiment e, long seed) {
        List<FaultSpec> specs = new ArrayList<>();
        for (FaultPlan f : e.faults()) {
            FaultSpec s = f.toSpec(0);
            specs.add(new FaultSpec(s.id(), s.type(), s.route(), s.probability(), s.param(), s.phase(), 0, 0));
        }
        FaultInjector inj = new FaultInjector();
        inj.configure(seed, specs);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < e.orders(); i++) {
            String id = orderId(i);
            for (String route : List.of("/reserve", "/charge", "/commit", "enqueue", "process", "ack")) {
                var d = inj.decide(route, id, 1, 0);
                if (!d.applied().isEmpty()) sb.append(id).append(route).append(d.applied()).append(';');
            }
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String orderId(int i) {
        return String.format("o%05d", i);
    }

    // ------------------------------------------------------------------------------------------------ one trial

    private static final class Order {
        final String id;
        final long scheduledMs;
        long startMs;
        double latencyMs;
        int status;
        boolean ok;
        String error = "";
        String spanId;

        Order(String id, long scheduledMs) {
            this.id = id;
            this.scheduledMs = scheduledMs;
        }
    }

    private static final class Trial {
        int index;
        long seed;
        final Map<String, Double> m = new LinkedHashMap<>();
        Stats.Summary latencyAll, latencyOk;
        final List<Object> timeline = new ArrayList<>();
        final Map<String, Long> faultsFired = new TreeMap<>();
        final List<String> sloFailures = new ArrayList<>();
        List<Object> sampleTraces = List.of();

        Map<String, Object> toJson() {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("trial", index);
            o.put("seed", seed);
            o.put("metrics", m);
            o.put("latencyAll", latencyAll);
            o.put("latencyOk", latencyOk);
            o.put("timeline", timeline);
            o.put("faultsFired", faultsFired);
            o.put("sloFailures", sloFailures);
            return o;
        }
    }

    private Trial runTrial(Experiment e, Variant v, int index, long seed) {
        quiesce();
        long t0 = System.currentTimeMillis() + LEAD_MS;
        queue.reset();
        configure(e, v, seed, t0);

        int n = e.orders();
        List<Order> orders = new ArrayList<>(n);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) orders.add(new Order(orderId(i), i * 1000L / e.ratePerSec()));
        sleepUntil(t0);
        for (Order o : orders) {
            Thread.ofVirtual().start(() -> {
                try {
                    sleepUntil(t0 + o.scheduledMs);
                    sendOrder(o);
                } finally {
                    done.countDown();
                }
            });
        }
        await(done, e.loadDurationMs() + CLIENT_TIMEOUT_MS + 5000);
        long loadEnd = System.currentTimeMillis();

        // Let the asynchronous half finish (or give up): drain time is itself a result.
        QueueStats qs = queue.stats();
        while (qs.pending() > 0 && System.currentTimeMillis() - loadEnd < e.drainMs()) {
            sleep(50);
            qs = queue.stats();
        }
        double drainMs = System.currentTimeMillis() - loadEnd;
        // work abandoned by timed-out callers can still be running on a server; wait it out before reading ledgers
        quiesce();
        qs = queue.stats();

        Trial tr = new Trial();
        tr.index = index;
        tr.seed = seed;
        score(e, tr, orders, qs, drainMs, t0);
        tr.sampleTraces = index == 0 ? sampleTraces(orders, tr) : List.of();
        evaluate(e.slo(), tr, e);
        return tr;
    }

    private void configure(Experiment e, Variant v, long seed, long t0) {
        for (String svc : List.of("payments", "inventory", "gateway", "worker")) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("reset", true);
            cfg.put("seed", seed);
            if (e.tunings().containsKey(svc)) cfg.put("tuning", e.tunings().get(svc));
            List<Object> faults = new ArrayList<>();
            for (FaultPlan f : e.faults()) if (f.service().equals(svc)) faults.add(f.toSpec(t0));
            cfg.put("faults", faults);
            if (svc.equals("gateway")) {
                cfg.put("policy", v.policy());
                cfg.put("queue", v.queue());
            }
            if (svc.equals("worker")) cfg.put("queue", v.queue());
            control(urlOf(svc), "POST", "/_faultline/configure", Json.write(cfg));
        }
    }

    private String urlOf(String svc) {
        return switch (svc) {
            case "payments" -> ep.payments();
            case "inventory" -> ep.inventory();
            case "gateway" -> ep.gateway();
            default -> ep.worker();
        };
    }

    private void sendOrder(Order o) {
        o.startMs = System.currentTimeMillis();
        long t0 = System.nanoTime();
        o.spanId = "client-" + o.id;
        String body = Json.write(Map.of("orderId", o.id, "amount", 100));
        HttpRequest req = HttpRequest.newBuilder(URI.create(ep.gateway() + "/order"))
                .timeout(Duration.ofMillis(CLIENT_TIMEOUT_MS)).header("Content-Type", "application/json")
                .header(TraceCtx.H_TRACE, TraceCtx.traceIdFor(o.id)).header(TraceCtx.H_PARENT, o.spanId)
                .header(TraceCtx.H_KEY, o.id).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            o.status = r.statusCode();
            o.ok = o.status == 200;
            if (!o.ok) {
                try {
                    Map<String, Object> eb = Json.parseObject(r.body());
                    o.error = Json.str(eb, "error", "http_" + o.status) + "/" + Json.str(eb, "cause", "");
                } catch (RuntimeException ex) {
                    o.error = "http_" + o.status;
                }
            }
        } catch (HttpTimeoutException ex) {
            o.error = "client_timeout";
        } catch (IOException ex) {
            o.error = "connection_error";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            o.error = "interrupted";
        }
        o.latencyMs = (System.nanoTime() - t0) / 1e6;
    }

    // ------------------------------------------------------------------------------------------------ scoring

    @SuppressWarnings("unchecked")
    private void score(Experiment e, Trial tr, List<Order> orders, QueueStats qs, double drainMs, long t0) {
        Map<String, Object> payLedger = getJson(ep.payments(), "/_faultline/ledger");
        Map<String, Object> invLedger = getJson(ep.inventory(), "/_faultline/ledger");
        Map<String, Integer> charges = counts(Json.map(payLedger, "charges"));
        Map<String, Integer> ships = counts(Json.map(invLedger, "shipments"));
        Map<String, Long> shipAt = firstMs(Json.map(invLedger, "shipments"));
        List<Double> shipLags = new ArrayList<>();
        long lastDelayedShipMs = Long.MIN_VALUE;

        int n = orders.size();
        int accepted = 0, doubleCharged = 0, extraCharges = 0, doubleShipped = 0, extraShips = 0, lost = 0,
                chargedNotAccepted = 0;
        List<Double> all = new ArrayList<>(), okLat = new ArrayList<>();
        for (Order o : orders) {
            all.add(o.latencyMs);
            if (o.ok) {
                accepted++;
                okLat.add(o.latencyMs);
            }
            int c = charges.getOrDefault(o.id, 0), s = ships.getOrDefault(o.id, 0);
            if (c > 1) {
                doubleCharged++;
                extraCharges += c - 1;
            }
            if (s > 1) {
                doubleShipped++;
                extraShips += s - 1;
            }
            if (o.ok && s == 0) lost++;
            if (!o.ok && c > 0) chargedNotAccepted++;
            // Ship lag: from "the customer was told accepted" to "the shipment actually happened".
            Long shippedAt = shipAt.get(o.id);
            if (o.ok && shippedAt != null) {
                double lag = Math.max(0, shippedAt - (o.startMs + (long) o.latencyMs));
                shipLags.add(lag);
                if (lag > DELAYED_SHIP_MS) lastDelayedShipMs = Math.max(lastDelayedShipMs, shippedAt);
            }
        }
        Stats.Summary shipLag = Stats.summarize(shipLags);
        tr.latencyAll = Stats.summarize(all);
        tr.latencyOk = Stats.summarize(okLat);

        Map<String, Object> gw = getJson(ep.gateway(), "/_faultline/metrics");
        Map<String, Object> pay = getJson(ep.payments(), "/_faultline/metrics");
        Map<String, Object> inv = getJson(ep.inventory(), "/_faultline/metrics");
        Map<String, Object> wk = getJson(ep.worker(), "/_faultline/metrics");
        Map<String, Object> gc = Json.map(gw, "counters"), wc = Json.map(wk, "counters");
        for (Map<String, Object> svc : List.of(gw, pay, inv, wk)) {
            Map<String, Object> c = Json.map(svc, "counters");
            if (c == null) continue;
            c.forEach((k, val) -> {
                if (k.startsWith("fault.") && val instanceof Number num) tr.faultsFired.merge(k.substring(6), num.longValue(), Long::sum);
            });
        }

        long payCalls = Json.lng(gc, "calls.payments", 0), payAttempts = Json.lng(gc, "attempts.payments", 0);
        int opens = 0;
        Map<String, Object> breakers = Json.map(gw, "breakers");
        if (breakers != null) {
            for (Object list : breakers.values()) {
                for (Object t : (List<Object>) list) if ("OPEN".equals(((Map<String, Object>) t).get("to"))) opens++;
            }
        }
        long redeliveries = 0, dupJobs = 0;
        for (Map.Entry<String, Integer> d : qs.deliveries().entrySet()) redeliveries += Math.max(0, d.getValue() - qs.enqueues().getOrDefault(d.getKey(), 1));
        for (int cnt : qs.enqueues().values()) dupJobs += Math.max(0, cnt - 1);

        Map<String, Double> m = tr.m;
        m.put("orders", (double) n);
        m.put("accepted", (double) accepted);
        m.put("successRate", accepted / (double) n);
        m.put("p50", tr.latencyAll.p50());
        m.put("p95", tr.latencyAll.p95());
        m.put("p99", tr.latencyAll.p99());
        m.put("maxLatency", tr.latencyAll.max());
        m.put("okP99", tr.latencyOk.p99());
        m.put("goodputPerSec", accepted / (e.loadDurationMs() / 1000.0));
        m.put("doubleCharged", (double) doubleCharged);
        m.put("extraCharges", (double) extraCharges);
        m.put("chargedNotAccepted", (double) chargedNotAccepted);
        m.put("doubleShipped", (double) doubleShipped);
        m.put("extraShipments", (double) extraShips);
        m.put("lostOrders", (double) lost);
        m.put("deadJobs", (double) qs.byState().getOrDefault("dead", 0L));
        m.put("pendingAfterDrain", (double) qs.pending());
        m.put("drainMs", drainMs);
        m.put("shipLagP50", shipLag.p50());
        m.put("shipLagP99", shipLag.p99());
        m.put("shipLagMax", shipLag.max());
        m.put("redeliveries", (double) redeliveries);
        m.put("duplicateJobs", (double) dupJobs);
        m.put("paymentsAmplification", payCalls == 0 ? 1.0 : payAttempts / (double) payCalls);
        m.put("paymentsRequests", (double) Json.lng(Json.map(pay, "counters"), "requests", 0));
        m.put("breakerOpens", (double) opens);
        m.put("breakerRejected", (double) Json.lng(gc, "breaker_rejected.payments", 0));
        m.put("retries", (double) (Json.lng(gc, "retries.payments", 0) + Json.lng(gc, "retries.inventory", 0)));
        m.put("staleAcks", (double) Json.lng(wc, "stale_ack_rejected", 0));
        m.put("lostAcks", (double) Json.lng(wc, "ack_lost", 0));

        // timeline + fault window + recovery
        int buckets = (int) (e.loadDurationMs() / BUCKET_MS) + 1;
        int[] tot = new int[buckets], ok = new int[buckets], lagN = new int[buckets];
        long[] lagSum = new long[buckets];
        for (Order o : orders) {
            int b = (int) Math.min(buckets - 1, o.scheduledMs / BUCKET_MS);
            tot[b]++;
            if (o.ok) ok[b]++;
            Long shippedAt = shipAt.get(o.id);
            if (o.ok && shippedAt != null) {
                lagSum[b] += Math.max(0, shippedAt - (o.startMs + (long) o.latencyMs));
                lagN[b]++;
            }
        }
        for (int b = 0; b < buckets; b++) {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("t", b * BUCKET_MS);
            pt.put("n", tot[b]);
            pt.put("ok", ok[b]);
            pt.put("lagSum", lagSum[b]); // summed across trials by the report: mean = sum(lagSum) / sum(lagN)
            pt.put("lagN", lagN[b]);
            tr.timeline.add(pt);
        }
        double window = Double.NaN, recovery = Double.NaN;
        if (e.windowEndMs() > 0) {
            int in = 0, inOk = 0;
            for (Order o : orders) {
                if (o.scheduledMs >= e.windowStartMs() && o.scheduledMs < e.windowEndMs()) {
                    in++;
                    if (o.ok) inOk++;
                }
            }
            window = in == 0 ? Double.NaN : inOk / (double) in;
            for (int b = (int) ((e.windowEndMs() + BUCKET_MS - 1) / BUCKET_MS); b < buckets; b++) {
                boolean good = tot[b] > 0 && ok[b] / (double) tot[b] >= 0.95;
                boolean nextGood = b + 1 >= buckets || tot[b + 1] == 0 || ok[b + 1] / (double) tot[b + 1] >= 0.95;
                if (good && nextGood) {
                    recovery = Math.max(0, b * BUCKET_MS - e.windowEndMs());
                    break;
                }
            }
            // "Recovered" also means the asynchronous side caught up: the last order whose shipment was delayed by
            // more than DELAYED_SHIP_MS must have shipped. This is what a backlog, or a job stranded behind a long
            // visibility timeout, looks like from the customer's side.
            if (!Double.isNaN(recovery) && lastDelayedShipMs != Long.MIN_VALUE) {
                recovery = Math.max(recovery, lastDelayedShipMs - (t0 + e.windowEndMs()));
            }
            m.put("windowSuccessRate", window);
            m.put("recoveryMs", recovery);
        } else {
            m.put("windowSuccessRate", Double.NaN);
            m.put("recoveryMs", Double.NaN);
        }
    }

    private void evaluate(Slo s, Trial tr, Experiment e) {
        Map<String, Double> m = tr.m;
        List<String> f = tr.sloFailures;
        if (e.slo().equals(Slo.NONE)) return;
        if (s.minSuccessRate() >= 0 && m.get("successRate") < s.minSuccessRate())
            f.add(String.format("success rate %.1f%% < %.1f%%", m.get("successRate") * 100, s.minSuccessRate() * 100));
        if (s.maxP99Ms() >= 0 && m.get("p99") > s.maxP99Ms())
            f.add(String.format("p99 %.0fms > %.0fms", m.get("p99"), s.maxP99Ms()));
        if (s.maxDoubleCharged() >= 0 && m.get("doubleCharged") > s.maxDoubleCharged())
            f.add(m.get("doubleCharged").intValue() + " customers charged twice");
        if (s.maxDoubleShipped() >= 0 && m.get("doubleShipped") > s.maxDoubleShipped())
            f.add(m.get("doubleShipped").intValue() + " orders shipped twice");
        if (s.maxLostOrders() >= 0 && m.get("lostOrders") > s.maxLostOrders())
            f.add(m.get("lostOrders").intValue() + " accepted orders never shipped");
        if (s.maxDeadJobs() >= 0 && m.get("deadJobs") > s.maxDeadJobs())
            f.add(m.get("deadJobs").intValue() + " jobs dead-lettered");
        if (s.maxDrainMs() >= 0 && (m.get("drainMs") > s.maxDrainMs() || m.get("pendingAfterDrain") > 0))
            f.add(String.format("queue drained in %.0fms (limit %.0fms)", m.get("drainMs"), s.maxDrainMs()));
        if (s.maxRecoveryMs() >= 0) {
            double r = m.get("recoveryMs");
            if (Double.isNaN(r)) f.add("never recovered before the load ended");
            else if (r > s.maxRecoveryMs()) f.add(String.format("recovery took %.0fms (limit %.0fms)", r, s.maxRecoveryMs()));
        }
    }

    // ------------------------------------------------------------------------------------------------ traces

    private List<Object> sampleTraces(List<Order> orders, Trial tr) {
        Map<String, List<Span>> byTrace = new HashMap<>();
        for (String svc : List.of("gateway", "payments", "inventory", "worker")) {
            String base = urlOf(svc);
            List<Object> raw = getJsonList(base, "/_faultline/spans");
            for (Object o : raw) {
                @SuppressWarnings("unchecked")
                Span s = Span.fromJson((Map<String, Object>) o);
                byTrace.computeIfAbsent(s.traceId(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<String, Order> byId = new HashMap<>();
        for (Order o : orders) {
            byId.put(TraceCtx.traceIdFor(o.id), o);
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("http", Integer.toString(o.status));
            if (!o.error.isEmpty()) tags.put("failure", o.error);
            byTrace.computeIfAbsent(TraceCtx.traceIdFor(o.id), k -> new ArrayList<>()).add(new Span(
                    TraceCtx.traceIdFor(o.id), o.spanId, "", "client", "POST /order", o.startMs, o.latencyMs,
                    o.ok ? "ok" : o.error, tags));
        }

        Map<String, Integer> charges = counts(Json.map(getJson(ep.payments(), "/_faultline/ledger"), "charges"));
        Map<String, Integer> ships = counts(Json.map(getJson(ep.inventory(), "/_faultline/ledger"), "shipments"));

        List<Object> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        pick(out, used, "Double-charged customer", orders, byTrace,
                o -> charges.getOrDefault(o.id, 0) > 1, Comparator.comparingInt(o -> -spanCount(byTrace, o)));
        pick(out, used, "Shipped twice", orders, byTrace,
                o -> ships.getOrDefault(o.id, 0) > 1, Comparator.comparingInt(o -> -spanCount(byTrace, o)));
        pick(out, used, "Accepted but never shipped", orders, byTrace,
                o -> o.ok && ships.getOrDefault(o.id, 0) == 0, Comparator.comparingInt(o -> -spanCount(byTrace, o)));
        pick(out, used, "Failed order", orders, byTrace,
                o -> !o.ok, Comparator.comparingInt(o -> -spanCount(byTrace, o)));
        pick(out, used, "Succeeded after retries", orders, byTrace,
                o -> o.ok && callSpans(byTrace, o) > 2, Comparator.comparingInt(o -> -callSpans(byTrace, o)));
        pick(out, used, "Slowest successful order", orders, byTrace,
                o -> o.ok, Comparator.comparingDouble(o -> -o.latencyMs));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    /** Outbound call attempts the gateway made for this order; a healthy order makes exactly two (inventory, payments). */
    private static int callSpans(Map<String, List<Span>> byTrace, Order o) {
        List<Span> l = byTrace.get(TraceCtx.traceIdFor(o.id));
        return l == null ? 0 : (int) l.stream().filter(sp -> sp.service().equals("gateway") && sp.name().startsWith("call ")).count();
    }

    private static int spanCount(Map<String, List<Span>> byTrace, Order o) {
        List<Span> l = byTrace.get(TraceCtx.traceIdFor(o.id));
        return l == null ? 0 : l.size();
    }

    private void pick(List<Object> out, Set<String> used, String kind, List<Order> orders,
                      Map<String, List<Span>> byTrace, java.util.function.Predicate<Order> pred,
                      Comparator<Order> order) {
        Order best = orders.stream().filter(o -> !used.contains(o.id)).filter(pred).sorted(order).findFirst().orElse(null);
        if (best == null) return;
        used.add(best.id);
        List<Span> spans = new ArrayList<>(byTrace.getOrDefault(TraceCtx.traceIdFor(best.id), List.of()));
        spans.sort(Comparator.comparingLong(Span::startMs).thenComparing(Span::spanId));
        if (spans.size() > 40) spans = spans.subList(0, 40);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("kind", kind);
        t.put("orderId", best.id);
        t.put("traceId", TraceCtx.traceIdFor(best.id));
        t.put("spans", spans);
        out.add(t);
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    /** Wait until no service is serving (or queueing) a request and no worker is mid-job. */
    private void quiesce() {
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            long active = 0;
            for (String svc : List.of("payments", "inventory", "gateway", "worker")) {
                try {
                    Map<String, Object> m = getJson(urlOf(svc), "/_faultline/metrics");
                    active += Json.lng(m, "active", 0) + Json.lng(m, "activeJobs", 0);
                } catch (RuntimeException ex) {
                    // a crashed service has nothing in flight that we can still observe
                }
            }
            if (active == 0) return;
            sleep(50);
        }
        log.accept("   warning: services still busy after 12s; results of the next trial may be contaminated");
    }

    private void warmup() {
        log.accept("warming up the JVM...");
        Experiment w = new Experiment("warmup", "", "", "", 150, 100, 1, Map.of(), List.of(),
                List.of(), Slo.NONE, 3000);
        Variant v = new Variant("warm", "warm", "", io.faultline.resilience.ResiliencePolicy.NAIVE,
                io.faultline.queue.QueueConfig.DEFAULT);
        runTrial(w, v, 0, 1);
    }

    private Map<String, Object> getJson(String base, String path) {
        return Json.parseObject(control(base, "GET", path, null));
    }

    private List<Object> getJsonList(String base, String path) {
        Object o = Json.parse(control(base, "GET", path, null));
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) o;
        return l;
    }

    private String control(String base, String method, String path, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IllegalStateException(method + " " + base + path + " -> " + r.statusCode() + " " + r.body());
            return r.body();
        } catch (IOException ex) {
            throw new IllegalStateException(method + " " + base + path + " failed: " + ex, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Long> firstMs(Map<String, Object> ledgerSection) {
        Map<String, Long> out = new HashMap<>();
        if (ledgerSection == null) return out;
        ledgerSection.forEach((k, v) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) v;
            long t = Json.lng(e, "firstMs", 0);
            if (t > 0) out.put(k, t);
        });
        return out;
    }

    private static Map<String, Integer> counts(Map<String, Object> ledgerSection) {
        Map<String, Integer> out = new HashMap<>();
        if (ledgerSection == null) return out;
        ledgerSection.forEach((k, v) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) v;
            out.put(k, Json.integer(e, "count", 0));
        });
        return out;
    }

    private static void sleepUntil(long epochMs) {
        long d = epochMs - System.currentTimeMillis();
        if (d > 0) sleep(d);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch l, long ms) {
        try {
            if (!l.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("load generator did not finish within " + ms + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

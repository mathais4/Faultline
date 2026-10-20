package io.faultline.service;

import io.faultline.core.Json;
import io.faultline.fault.FaultInjector;
import io.faultline.queue.JobQueue;
import io.faultline.queue.QueueConfig;
import io.faultline.resilience.CallResult;
import io.faultline.resilience.CircuitBreaker;
import io.faultline.resilience.ResiliencePolicy;
import io.faultline.resilience.ResilientClient;
import io.faultline.trace.Span;
import io.faultline.trace.TraceCtx;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public entry point. POST /order does, in order:
 * <ol>
 *   <li>synchronous call to inventory /reserve,</li>
 *   <li>synchronous call to payments /charge,</li>
 *   <li>asynchronous hand-off: enqueue a fulfilment job for the worker.</li>
 * </ol>
 * Both synchronous calls go through a {@link ResilientClient} configured with the policy under test.
 */
public final class GatewayService extends ServiceNode {
    private final String inventoryUrl, paymentsUrl;
    private final JobQueue queue;
    private volatile ResilientClient inventory, payments;
    private volatile ResiliencePolicy policy = ResiliencePolicy.NAIVE;
    private volatile QueueConfig queueConfig = QueueConfig.DEFAULT;

    public GatewayService(int port, String inventoryUrl, String paymentsUrl, JobQueue queue) {
        super("gateway", port);
        this.inventoryUrl = inventoryUrl;
        this.paymentsUrl = paymentsUrl;
        this.queue = queue;
        rebuildClients();
    }

    private void rebuildClients() {
        inventory = new ResilientClient(name, "inventory", inventoryUrl, policy, spans, metrics, seed);
        payments = new ResilientClient(name, "payments", paymentsUrl, policy, spans, metrics, seed);
    }

    @Override
    protected void onConfigure(Map<String, Object> cfg) {
        if (cfg.containsKey("policy")) policy = ResiliencePolicy.fromJson(Json.map(cfg, "policy"));
        if (cfg.containsKey("queue")) queueConfig = QueueConfig.fromJson(Json.map(cfg, "queue"));
        rebuildClients(); // fresh breaker + budget state for every trial
    }

    @Override
    protected void onReset() {
        policy = ResiliencePolicy.NAIVE;
        queueConfig = QueueConfig.DEFAULT;
        rebuildClients();
    }

    @Override
    protected Response handle(Request req) {
        if (!req.path.equals("/order")) return Response.error(404, "not found");
        String orderId = Json.str(req.json(), "orderId", "");
        if (orderId.isEmpty()) return Response.error(400, "orderId required");

        CallResult reserve = inventory.post("/reserve", req.body, req.ctx);
        if (!reserve.ok()) return failure(502, "inventory_unavailable", reserve);

        CallResult charge = payments.post("/charge", req.body, req.ctx);
        if (!charge.ok()) return failure(502, "payment_failed", charge);

        return enqueue(orderId, req.ctx);
    }

    private Response enqueue(String orderId, TraceCtx ctx) {
        long startMs = System.currentTimeMillis();
        long t0 = System.nanoTime();
        String spanId = spans.newSpanId();
        Map<String, String> tags = new LinkedHashMap<>();
        String status = "ok";
        try {
            QueueConfig qc = queueConfig;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", orderId);
            payload.put("traceId", ctx.traceId());
            payload.put("parentSpanId", spanId);
            String json = Json.write(payload);
            String dedupKey = qc.enqueueDedup() ? orderId : null;

            FaultInjector.Decision d = faults.decide("enqueue", orderId, 1, startMs);
            Optional<Long> first = queue.enqueue(orderId, dedupKey, json, qc.maxAttempts());
            tags.put("jobId", first.map(String::valueOf).orElse("deduplicated"));
            if (d.duplicate()) {
                // producer retried / network duplicated the publish
                Optional<Long> second = queue.enqueue(orderId, dedupKey, json, qc.maxAttempts());
                tags.put("duplicatePublish", second.isPresent() ? "accepted" : "dropped_by_dedup");
                metrics.inc(second.isPresent() ? "duplicate_publish_accepted" : "duplicate_publish_dropped");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "accepted");
            out.put("orderId", orderId);
            return Response.ok(out);
        } catch (RuntimeException e) {
            status = "error";
            tags.put("exception", e.getMessage() == null ? e.toString() : e.getMessage());
            metrics.inc("enqueue_failed");
            return Response.error(503, "enqueue_failed");
        } finally {
            spans.add(new Span(ctx.traceId(), spanId, ctx.parentSpanId(), name, "enqueue", startMs,
                    (System.nanoTime() - t0) / 1e6, status, tags));
        }
    }

    private static Response failure(int status, String error, CallResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("cause", r.failure());
        m.put("attempts", r.attemptsSent());
        return Response.of(status, m);
    }

    @Override
    protected Map<String, Object> extraMetrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> breakers = new LinkedHashMap<>();
        for (ResilientClient c : List.of(inventory, payments)) {
            List<CircuitBreaker.Transition> t = c.breakerTransitions();
            if (!t.isEmpty()) breakers.put(c.target(), t);
        }
        out.put("breakers", breakers);
        out.put("policy", policy);
        return out;
    }
}

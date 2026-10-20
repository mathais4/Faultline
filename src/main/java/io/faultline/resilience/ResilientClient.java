package io.faultline.resilience;

import io.faultline.core.Hashing;
import io.faultline.core.Metrics;
import io.faultline.trace.Span;
import io.faultline.trace.SpanStore;
import io.faultline.trace.TraceCtx;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * An HTTP client wrapped in a {@link ResiliencePolicy}: per-attempt timeout, retries with backoff, optional
 * circuit breaker and retry budget, optional idempotency keys. One instance per (caller, target) so each target
 * gets its own breaker and budget, exactly as a production service would configure it.
 */
public final class ResilientClient {

    private static final HttpClient SHARED = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private final String caller, target, baseUrl;
    private final ResiliencePolicy policy;
    private final CircuitBreaker breaker;
    private final RetryBudget budget;
    private final SpanStore spans;
    private final Metrics metrics;
    private final long seed;

    public ResilientClient(String caller, String target, String baseUrl, ResiliencePolicy policy, SpanStore spans,
                           Metrics metrics, long seed) {
        this.caller = caller;
        this.target = target;
        this.baseUrl = baseUrl;
        this.policy = policy;
        this.spans = spans;
        this.metrics = metrics;
        this.seed = seed;
        this.breaker = policy.breaker() == null ? null : new CircuitBreaker(policy.breaker());
        this.budget = policy.budget() == null ? null
                : new RetryBudget(policy.budget().ratio(), policy.budget().initial(), policy.budget().max());
    }

    public List<CircuitBreaker.Transition> breakerTransitions() {
        return breaker == null ? List.of() : breaker.transitions();
    }

    public ResiliencePolicy policy() {
        return policy;
    }

    public String target() {
        return target;
    }

    public CallResult post(String path, String body, TraceCtx ctx) {
        return post(path, body, ctx, 1);
    }

    /**
     * @param attemptBase attempt number reported for the first send. A queue worker passes the delivery count so a
     *                    redelivered job is a fresh roll of the fault dice instead of a guaranteed repeat failure.
     */
    public CallResult post(String path, String body, TraceCtx ctx, int attemptBase) {
        long callStart = System.nanoTime();
        metrics.inc("calls." + target);
        if (budget != null) budget.onRequest();

        CallResult last = null;
        int sent = 0;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (attempt > 1 && (System.nanoTime() - callStart) / 1_000_000 >= policy.deadlineMs()) {
                metrics.inc("deadline_exceeded." + target);
                break;
            }

            String spanId = spans.newSpanId();
            long startMs = System.currentTimeMillis();
            long t0 = System.nanoTime();
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("attempt", Integer.toString(attemptBase + attempt - 1));
            tags.put("target", target);

            if (breaker != null && !breaker.allow()) {
                metrics.inc("breaker_rejected." + target);
                spans.add(new Span(ctx.traceId(), spanId, ctx.parentSpanId(), caller, "call " + target + path,
                        startMs, 0, "breaker_open", tags));
                return CallResult.failed(0, null, "breaker_open", sent);
            }

            sent++;
            metrics.inc("attempts." + target);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMillis(policy.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header(TraceCtx.H_TRACE, ctx.traceId())
                    .header(TraceCtx.H_PARENT, spanId)
                    .header(TraceCtx.H_ATTEMPT, Integer.toString(attemptBase + attempt - 1))
                    .header(TraceCtx.H_KEY, ctx.key())
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (policy.idempotencyKeys()) rb.header(TraceCtx.H_IDEMPOTENCY, ctx.key());

            String failure = null;
            int status = 0;
            String respBody = null;
            try {
                HttpResponse<String> resp = SHARED.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                status = resp.statusCode();
                respBody = resp.body();
                if (status >= 500 || status == 429 || status == 408) failure = "http_" + (status == 429 ? "429" : "5xx");
                else if (status >= 400) failure = "http_4xx";
            } catch (HttpTimeoutException e) {
                failure = "timeout";
            } catch (ConnectException e) {
                failure = "connect_refused";
            } catch (IOException e) {
                failure = "connection_lost";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "interrupted";
            }

            double durMs = (System.nanoTime() - t0) / 1e6;
            boolean ok = failure == null;
            // 4xx is the caller's mistake, not evidence the dependency is unhealthy: it must not trip a breaker.
            boolean dependencyHealthy = ok || "http_4xx".equals(failure);
            if (breaker != null) breaker.record(dependencyHealthy);

            tags.put("http", Integer.toString(status));
            if (failure != null) tags.put("failure", failure);
            spans.add(new Span(ctx.traceId(), spanId, ctx.parentSpanId(), caller, "call " + target + path, startMs,
                    durMs, ok ? "ok" : failure, tags));

            if (ok) return CallResult.success(status, respBody, sent);
            last = CallResult.failed(status, respBody, failure, sent);
            if ("http_4xx".equals(failure) || "interrupted".equals(failure)) return last;
            if (attempt >= policy.maxAttempts()) break;

            if (budget != null && !budget.tryAcquireRetry()) {
                metrics.inc("retry_budget_exhausted." + target);
                break;
            }
            long delay = policy.retry().delayMs(attempt, policy.baseDelayMs(), policy.maxDelayMs(),
                    Hashing.unit(seed, "backoff", ctx.key(), target, Integer.toString(attempt)));
            metrics.inc("retries." + target);
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last == null ? CallResult.failed(0, null, "no_attempt", sent) : last;
    }
}

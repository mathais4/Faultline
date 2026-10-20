package io.faultline.resilience;

import io.faultline.core.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything a caller does to survive a flaky dependency. This is the unit being compared in experiments.
 *
 * @param timeoutMs         per-attempt timeout
 * @param maxAttempts       total attempts including the first
 * @param retry             backoff strategy between attempts
 * @param baseDelayMs       backoff base
 * @param maxDelayMs        backoff cap
 * @param deadlineMs        stop retrying once the whole call has taken this long
 * @param breaker           circuit breaker config, or null for none
 * @param budget            retry budget config, or null for none
 * @param idempotencyKeys   send Idempotency-Key so servers can de-duplicate retried side effects
 */
public record ResiliencePolicy(long timeoutMs, int maxAttempts, RetryStrategy retry, long baseDelayMs,
                               long maxDelayMs, long deadlineMs, CircuitBreaker.Config breaker, Budget budget,
                               boolean idempotencyKeys) implements Json.Jsonable {

    public record Budget(double ratio, double initial, double max) implements Json.Jsonable {
        public static final Budget DEFAULT = new Budget(0.2, 5, 20);

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ratio", ratio);
            m.put("initial", initial);
            m.put("max", max);
            return m;
        }

        static Budget fromJson(Map<String, Object> m) {
            if (m == null) return null;
            return new Budget(Json.dbl(m, "ratio", DEFAULT.ratio), Json.dbl(m, "initial", DEFAULT.initial),
                    Json.dbl(m, "max", DEFAULT.max));
        }
    }

    public static final ResiliencePolicy NAIVE =
            new ResiliencePolicy(1500, 1, RetryStrategy.NONE, 0, 0, 4000, null, null, false);

    public ResiliencePolicy withIdempotency(boolean on) {
        return new ResiliencePolicy(timeoutMs, maxAttempts, retry, baseDelayMs, maxDelayMs, deadlineMs, breaker,
                budget, on);
    }

    /** Short human label, e.g. "3x exp+jitter 100-1000ms, timeout 400ms, breaker, budget, idem". */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("timeout ").append(timeoutMs).append("ms");
        if (maxAttempts > 1) {
            sb.append(", ").append(maxAttempts).append(" attempts (").append(retry.name().toLowerCase().replace('_', '+'))
                    .append(' ').append(baseDelayMs).append('-').append(maxDelayMs).append("ms)");
        } else {
            sb.append(", no retry");
        }
        if (breaker != null) sb.append(", circuit breaker");
        if (budget != null) sb.append(", retry budget ").append((int) (budget.ratio() * 100)).append('%');
        if (idempotencyKeys) sb.append(", idempotency keys");
        return sb.toString();
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timeoutMs", timeoutMs);
        m.put("maxAttempts", maxAttempts);
        m.put("retry", retry);
        m.put("baseDelayMs", baseDelayMs);
        m.put("maxDelayMs", maxDelayMs);
        m.put("deadlineMs", deadlineMs);
        m.put("breaker", breaker);
        m.put("budget", budget);
        m.put("idempotencyKeys", idempotencyKeys);
        return m;
    }

    public static ResiliencePolicy fromJson(Map<String, Object> m) {
        if (m == null) return NAIVE;
        return new ResiliencePolicy(Json.lng(m, "timeoutMs", 1500), Json.integer(m, "maxAttempts", 1),
                RetryStrategy.valueOf(Json.str(m, "retry", "NONE")), Json.lng(m, "baseDelayMs", 0),
                Json.lng(m, "maxDelayMs", 0), Json.lng(m, "deadlineMs", 4000),
                Json.map(m, "breaker") == null ? null : CircuitBreaker.Config.fromJson(Json.map(m, "breaker")),
                Budget.fromJson(Json.map(m, "budget")), Json.bool(m, "idempotencyKeys", false));
    }
}

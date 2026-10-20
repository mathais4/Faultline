package io.faultline.queue;

import io.faultline.core.Json;
import io.faultline.resilience.RetryStrategy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The knobs of the asynchronous half of the system, compared in the queue experiments.
 *
 * @param visibilityMs       how long a claimed job stays hidden before being redelivered
 * @param maxAttempts        deliveries before the job is dead-lettered
 * @param backoff            delay strategy applied when a worker nacks a failed job
 * @param workers            concurrent worker loops
 * @param enqueueDedup       producer attaches a dedup key so a duplicate publish is dropped by the queue
 * @param consumerIdempotent consumer sends an Idempotency-Key so a duplicate delivery does not repeat the side effect
 */
public record QueueConfig(long visibilityMs, int maxAttempts, RetryStrategy backoff, long backoffBaseMs,
                          long backoffMaxMs, int workers, boolean enqueueDedup, boolean consumerIdempotent)
        implements Json.Jsonable {

    public static final QueueConfig DEFAULT =
            new QueueConfig(2000, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 2000, 4, true, true);

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("visibility ").append(visibilityMs).append("ms, max ").append(maxAttempts).append(" attempts");
        sb.append(", backoff ").append(backoff == RetryStrategy.NONE ? "none"
                : backoff.name().toLowerCase().replace('_', '+') + " " + backoffBaseMs + "-" + backoffMaxMs + "ms");
        sb.append(", ").append(workers).append(" workers");
        sb.append(enqueueDedup ? ", enqueue dedup" : ", no enqueue dedup");
        sb.append(consumerIdempotent ? ", idempotent consumer" : ", non-idempotent consumer");
        return sb.toString();
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visibilityMs", visibilityMs);
        m.put("maxAttempts", maxAttempts);
        m.put("backoff", backoff);
        m.put("backoffBaseMs", backoffBaseMs);
        m.put("backoffMaxMs", backoffMaxMs);
        m.put("workers", workers);
        m.put("enqueueDedup", enqueueDedup);
        m.put("consumerIdempotent", consumerIdempotent);
        return m;
    }

    public static QueueConfig fromJson(Map<String, Object> m) {
        if (m == null) return DEFAULT;
        return new QueueConfig(Json.lng(m, "visibilityMs", DEFAULT.visibilityMs),
                Json.integer(m, "maxAttempts", DEFAULT.maxAttempts),
                RetryStrategy.valueOf(Json.str(m, "backoff", DEFAULT.backoff.name())),
                Json.lng(m, "backoffBaseMs", DEFAULT.backoffBaseMs),
                Json.lng(m, "backoffMaxMs", DEFAULT.backoffMaxMs), Json.integer(m, "workers", DEFAULT.workers),
                Json.bool(m, "enqueueDedup", DEFAULT.enqueueDedup),
                Json.bool(m, "consumerIdempotent", DEFAULT.consumerIdempotent));
    }
}

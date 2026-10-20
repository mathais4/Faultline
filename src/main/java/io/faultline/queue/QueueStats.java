package io.faultline.queue;

import io.faultline.core.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot used as ground truth by the experiment runner.
 *
 * @param byState    job count per state (queued, running, done, dead)
 * @param deliveries per order: how many times a job for it was handed to a worker
 * @param enqueues   per order: how many jobs were created (more than 1 = duplicate publish got through)
 */
public record QueueStats(Map<String, Long> byState, Map<String, Integer> deliveries, Map<String, Integer> enqueues)
        implements Json.Jsonable {

    public long pending() {
        return byState.getOrDefault("queued", 0L) + byState.getOrDefault("running", 0L);
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byState", byState);
        m.put("pending", pending());
        m.put("deliveries", deliveries);
        m.put("enqueues", enqueues);
        return m;
    }
}

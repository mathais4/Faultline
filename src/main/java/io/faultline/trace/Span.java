package io.faultline.trace;

import io.faultline.core.Json;
import io.faultline.core.Stats;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One timed operation in a request's life: a server handling a request, a client attempt, a job being processed.
 * Spans from every service share a traceId and link through parentId, so the experiment runner can stitch them
 * back into a single request trace.
 */
public record Span(String traceId, String spanId, String parentId, String service, String name, long startMs,
                   double durationMs, String status, Map<String, String> tags) implements Json.Jsonable {

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", traceId);
        m.put("spanId", spanId);
        m.put("parentId", parentId);
        m.put("service", service);
        m.put("name", name);
        m.put("startMs", startMs);
        m.put("durationMs", Stats.round(durationMs));
        m.put("status", status);
        m.put("tags", tags);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Span fromJson(Map<String, Object> m) {
        Map<String, String> tags = new LinkedHashMap<>();
        Map<String, Object> raw = Json.map(m, "tags");
        if (raw != null) raw.forEach((k, v) -> tags.put(k, String.valueOf(v)));
        return new Span(Json.str(m, "traceId", ""), Json.str(m, "spanId", ""), Json.str(m, "parentId", ""),
                Json.str(m, "service", ""), Json.str(m, "name", ""), Json.lng(m, "startMs", 0),
                Json.dbl(m, "durationMs", 0), Json.str(m, "status", ""), tags);
    }
}

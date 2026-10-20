package io.faultline.fault;

import io.faultline.core.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One injected fault, as sent to a service's control API.
 *
 * @param id          unique within an experiment; salts the deterministic fault decision
 * @param route       URL path ("/charge") or internal operation ("ack", "enqueue", "process"); "*" matches all
 * @param probability chance that a matching request is hit, decided deterministically per (order, attempt)
 * @param param       LATENCY: delay in ms. ERROR: HTTP status (0 = 500). Others: unused
 * @param fromMs      absolute epoch ms when the fault becomes active (0 = always)
 * @param untilMs     absolute epoch ms when it stops (0 = never)
 */
public record FaultSpec(String id, FaultType type, String route, double probability, long param, Phase phase,
                        long fromMs, long untilMs) implements Json.Jsonable {

    public enum Phase { BEFORE, AFTER }

    public boolean matchesRoute(String r) {
        return route.equals("*") || route.equals(r);
    }

    public boolean activeAt(long nowMs) {
        return nowMs >= fromMs && (untilMs == 0 || nowMs < untilMs);
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("route", route);
        m.put("probability", probability);
        m.put("param", param);
        m.put("phase", phase);
        m.put("fromMs", fromMs);
        m.put("untilMs", untilMs);
        return m;
    }

    public static FaultSpec fromJson(Map<String, Object> m) {
        return new FaultSpec(
                Json.str(m, "id", "fault"),
                FaultType.valueOf(Json.str(m, "type", "ERROR")),
                Json.str(m, "route", "*"),
                Json.dbl(m, "probability", 1.0),
                Json.lng(m, "param", 0),
                Phase.valueOf(Json.str(m, "phase", "BEFORE")),
                Json.lng(m, "fromMs", 0),
                Json.lng(m, "untilMs", 0));
    }
}

package io.faultline.fault;

import io.faultline.core.Hashing;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which faults hit a given request. Pure function of (seed, fault id, order key, attempt, time window),
 * so a seed reproduces the same fault schedule regardless of thread interleaving.
 */
public final class FaultInjector {

    /** The combined effect of every fault that fired for one request. */
    public record Decision(long beforeDelayMs, long afterDelayMs, int errorStatus, boolean dropBefore,
                           boolean dropAfter, boolean lostAck, boolean duplicate, List<String> applied) {
        public static final Decision NONE = new Decision(0, 0, 0, false, false, false, false, List.of());
    }

    private volatile List<FaultSpec> specs = List.of();
    private volatile long seed = 0;

    public void configure(long seed, List<FaultSpec> specs) {
        this.seed = seed;
        this.specs = List.copyOf(specs);
    }

    public void clear() {
        this.specs = List.of();
    }

    public List<FaultSpec> specs() {
        return specs;
    }

    public Decision decide(String route, String key, int attempt, long nowMs) {
        List<FaultSpec> all = specs;
        if (all.isEmpty()) return Decision.NONE;

        long before = 0, after = 0;
        int error = 0;
        boolean dropBefore = false, dropAfter = false, lostAck = false, duplicate = false;
        List<String> applied = null;

        for (FaultSpec f : all) {
            if (f.type() == FaultType.CRASH) continue; // crashes are scheduled by the service, not per request
            if (!f.matchesRoute(route) || !f.activeAt(nowMs)) continue;
            double u = Hashing.unit(seed, f.id(), key == null ? "" : key, Integer.toString(attempt));
            if (u >= f.probability()) continue;

            switch (f.type()) {
                case LATENCY -> {
                    if (f.phase() == FaultSpec.Phase.BEFORE) before += f.param();
                    else after += f.param();
                }
                case ERROR -> {
                    if (error == 0) error = f.param() == 0 ? 500 : (int) f.param();
                }
                case DROP -> {
                    if (f.phase() == FaultSpec.Phase.BEFORE) dropBefore = true;
                    else dropAfter = true;
                }
                case LOST_ACK -> lostAck = true;
                case DUPLICATE -> duplicate = true;
                case CRASH -> { }
            }
            if (applied == null) applied = new ArrayList<>(2);
            applied.add(f.id());
        }
        if (applied == null) return Decision.NONE;
        return new Decision(before, after, error, dropBefore, dropAfter, lostAck, duplicate, applied);
    }
}

package io.faultline.lab;

import io.faultline.fault.FaultSpec;
import io.faultline.fault.FaultType;
import io.faultline.queue.QueueConfig;
import io.faultline.resilience.ResiliencePolicy;
import io.faultline.service.ServiceNode.Tuning;
import java.util.List;
import java.util.Map;

/**
 * A reproducible experiment: one workload, one fault schedule, several variants of the thing being tested.
 * Every variant sees the same orders and the same planned faults (same seed), so differences in the results are
 * differences in the strategy, not in the luck of the draw.
 */
public record Experiment(String id, String title, String question, String description, int orders, int ratePerSec,
                         long seed, Map<String, Tuning> tunings, List<FaultPlan> faults, List<Variant> variants,
                         Slo slo, long drainMs) {

    /** One strategy under test: the synchronous-call policy and the queue configuration. */
    public record Variant(String id, String label, String description, ResiliencePolicy policy, QueueConfig queue) {}

    /**
     * What "it worked" means. A value of -1 disables that check. A variant WORKED only if every trial met every
     * enabled check, so a strategy that is fast but double-charges customers does not pass.
     */
    public record Slo(double minSuccessRate, double maxP99Ms, int maxDoubleCharged, int maxDoubleShipped,
                      int maxLostOrders, int maxDeadJobs, double maxRecoveryMs, double maxDrainMs) {
        public static final Slo NONE = new Slo(-1, -1, -1, -1, -1, -1, -1, -1);
    }

    /**
     * A fault with times relative to the start of the load. fromOffsetMs = untilOffsetMs = 0 means "all run long".
     */
    public record FaultPlan(String service, String id, FaultType type, String route, double probability, long param,
                            FaultSpec.Phase phase, long fromOffsetMs, long untilOffsetMs) {

        public boolean windowed() {
            return fromOffsetMs > 0 || untilOffsetMs > 0;
        }

        public FaultSpec toSpec(long loadStartMs) {
            long from = windowed() ? loadStartMs + fromOffsetMs : 0;
            long until = untilOffsetMs > 0 ? loadStartMs + untilOffsetMs : 0;
            return new FaultSpec(id, type, route, probability, param, phase, from, until);
        }

        public String describe() {
            String when = windowed()
                    ? "from t+" + fromOffsetMs + "ms to t+" + untilOffsetMs + "ms"
                    : "for the whole run";
            String what = switch (type) {
                case LATENCY -> "adds " + param + "ms " + (phase == FaultSpec.Phase.BEFORE ? "before doing the work"
                        : "after doing the work (response is late)");
                case ERROR -> "answers HTTP " + (param == 0 ? 500 : param) + " without doing the work";
                case DROP -> phase == FaultSpec.Phase.BEFORE ? "closes the connection without doing the work"
                        : "does the work, then closes the connection without responding";
                case CRASH -> "is down (connection refused)";
                case LOST_ACK -> "finishes the job but its acknowledgement never reaches the queue";
                case DUPLICATE -> "publishes the message twice";
            };
            String prob = type == FaultType.CRASH || probability >= 1.0 ? "" : Math.round(probability * 100) + "% of ";
            return service + " " + route + ": " + prob + (prob.isEmpty() ? "" : "requests ") + what + ", " + when;
        }

        public static FaultPlan error(String service, String id, String route, double p, int status) {
            return new FaultPlan(service, id, FaultType.ERROR, route, p, status, FaultSpec.Phase.BEFORE, 0, 0);
        }

        public static FaultPlan latency(String service, String id, String route, double p, long ms,
                                        FaultSpec.Phase phase, long from, long until) {
            return new FaultPlan(service, id, FaultType.LATENCY, route, p, ms, phase, from, until);
        }

        public static FaultPlan drop(String service, String id, String route, double p, FaultSpec.Phase phase) {
            return new FaultPlan(service, id, FaultType.DROP, route, p, 0, phase, 0, 0);
        }

        public static FaultPlan crash(String service, String id, long from, long until) {
            return new FaultPlan(service, id, FaultType.CRASH, "*", 1.0, 0, FaultSpec.Phase.BEFORE, from, until);
        }

        public static FaultPlan lostAck(String service, String id, double p) {
            return new FaultPlan(service, id, FaultType.LOST_ACK, "ack", p, 0, FaultSpec.Phase.AFTER, 0, 0);
        }

        public static FaultPlan duplicate(String service, String id, String route, double p) {
            return new FaultPlan(service, id, FaultType.DUPLICATE, route, p, 0, FaultSpec.Phase.BEFORE, 0, 0);
        }
    }

    /** Start of the window in which at least one fault is hard-on, or -1. Used for recovery measurements. */
    public long windowStartMs() {
        return faults.stream().filter(FaultPlan::windowed).mapToLong(FaultPlan::fromOffsetMs).min().orElse(-1);
    }

    public long windowEndMs() {
        return faults.stream().filter(f -> f.untilOffsetMs() > 0).mapToLong(FaultPlan::untilOffsetMs).max()
                .orElse(-1);
    }

    public long loadDurationMs() {
        return orders * 1000L / ratePerSec;
    }
}

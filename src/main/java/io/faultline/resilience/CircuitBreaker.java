package io.faultline.resilience;

import io.faultline.core.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Count-based sliding-window circuit breaker.
 *
 * CLOSED: calls flow; the last {@code windowSize} outcomes are tracked. Once at least {@code minimumCalls} are in
 * the window and the failure rate reaches {@code failureRateThreshold}, the breaker OPENs.
 * OPEN: calls are rejected immediately for {@code openMs}.
 * HALF_OPEN: up to {@code halfOpenPermits} probe calls are let through. All succeed -> CLOSED, any fails -> OPEN.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Config(int windowSize, int minimumCalls, double failureRateThreshold, long openMs,
                         int halfOpenPermits) implements Json.Jsonable {
        public static final Config DEFAULT = new Config(20, 10, 0.5, 500, 3);

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("windowSize", windowSize);
            m.put("minimumCalls", minimumCalls);
            m.put("failureRateThreshold", failureRateThreshold);
            m.put("openMs", openMs);
            m.put("halfOpenPermits", halfOpenPermits);
            return m;
        }

        public static Config fromJson(Map<String, Object> m) {
            if (m == null) return DEFAULT;
            return new Config(Json.integer(m, "windowSize", DEFAULT.windowSize),
                    Json.integer(m, "minimumCalls", DEFAULT.minimumCalls),
                    Json.dbl(m, "failureRateThreshold", DEFAULT.failureRateThreshold),
                    Json.lng(m, "openMs", DEFAULT.openMs),
                    Json.integer(m, "halfOpenPermits", DEFAULT.halfOpenPermits));
        }
    }

    public record Transition(long atMs, State from, State to) implements Json.Jsonable {
        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("atMs", atMs);
            m.put("from", from);
            m.put("to", to);
            return m;
        }
    }

    private final Config cfg;
    private final LongSupplier clock;
    private final boolean[] window; // true = failure
    private int filled, next, failures;
    private State state = State.CLOSED;
    private long openedAt;
    private int probesIssued, probesSucceeded;
    private final List<Transition> transitions = new ArrayList<>();
    private long rejected;

    public CircuitBreaker(Config cfg) {
        this(cfg, System::currentTimeMillis);
    }

    public CircuitBreaker(Config cfg, LongSupplier clock) {
        this.cfg = cfg;
        this.clock = clock;
        this.window = new boolean[cfg.windowSize()];
    }

    /** @return true if the call may proceed. A true result must be followed by record(). */
    public synchronized boolean allow() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.getAsLong() - openedAt >= cfg.openMs()) {
                    moveTo(State.HALF_OPEN);
                    probesIssued = 1;
                    probesSucceeded = 0;
                    return true;
                }
                rejected++;
                return false;
            case HALF_OPEN:
                if (probesIssued < cfg.halfOpenPermits()) {
                    probesIssued++;
                    return true;
                }
                rejected++;
                return false;
            default:
                return true;
        }
    }

    public synchronized void record(boolean success) {
        switch (state) {
            case CLOSED -> {
                if (filled == window.length) {
                    if (window[next]) failures--;
                } else {
                    filled++;
                }
                window[next] = !success;
                if (!success) failures++;
                next = (next + 1) % window.length;
                if (filled >= cfg.minimumCalls() && (double) failures / filled >= cfg.failureRateThreshold()) {
                    trip();
                }
            }
            case HALF_OPEN -> {
                if (!success) {
                    trip();
                } else if (++probesSucceeded >= cfg.halfOpenPermits()) {
                    resetWindow();
                    moveTo(State.CLOSED);
                }
            }
            case OPEN -> { /* late result from a call admitted before the trip: ignore */ }
        }
    }

    private void trip() {
        openedAt = clock.getAsLong();
        moveTo(State.OPEN);
    }

    private void resetWindow() {
        filled = next = failures = 0;
        java.util.Arrays.fill(window, false);
    }

    private void moveTo(State to) {
        transitions.add(new Transition(clock.getAsLong(), state, to));
        state = to;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long rejectedCalls() {
        return rejected;
    }

    public synchronized List<Transition> transitions() {
        return new ArrayList<>(transitions);
    }
}

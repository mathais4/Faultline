package io.faultline.resilience;

/**
 * Outcome of a resilient call.
 *
 * @param attemptsSent how many requests actually left the process (breaker rejections do not count)
 * @param failure      null on success; otherwise timeout, connect_refused, connection_lost, http_5xx, http_429,
 *                     http_4xx, breaker_open
 */
public record CallResult(boolean ok, int status, String body, String failure, int attemptsSent) {
    public static CallResult success(int status, String body, int attempts) {
        return new CallResult(true, status, body, null, attempts);
    }

    public static CallResult failed(int status, String body, String failure, int attempts) {
        return new CallResult(false, status, body, failure, attempts);
    }
}

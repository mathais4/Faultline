package io.faultline.resilience;

/** How long to wait between attempts. */
public enum RetryStrategy {
    /** Single attempt. */
    NONE,
    /** Same delay every time. */
    FIXED,
    /** base * 2^(n-1), capped. Every client that failed together retries together. */
    EXPONENTIAL,
    /** "Full jitter": uniform(0, exponential). Spreads retries out so failures do not re-synchronise. */
    EXPONENTIAL_JITTER;

    /**
     * @param failedAttempts number of attempts that have already failed (1 after the first failure)
     * @param unit           deterministic uniform [0,1) value for this (order, attempt); only used for jitter
     */
    public long delayMs(int failedAttempts, long baseMs, long capMs, double unit) {
        return switch (this) {
            case NONE -> 0;
            case FIXED -> baseMs;
            case EXPONENTIAL -> exp(failedAttempts, baseMs, capMs);
            case EXPONENTIAL_JITTER -> (long) (unit * exp(failedAttempts, baseMs, capMs));
        };
    }

    private static long exp(int failedAttempts, long baseMs, long capMs) {
        long d = baseMs;
        for (int i = 1; i < failedAttempts && d < capMs; i++) d *= 2;
        return Math.min(d, capMs);
    }
}

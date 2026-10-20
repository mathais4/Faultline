package io.faultline.resilience;

/**
 * Token-bucket cap on retry volume (the Finagle / Envoy "retry budget" idea).
 * Every first attempt deposits {@code ratio} tokens; every retry withdraws one. When the dependency is healthy
 * there are always enough tokens; when everything is failing, retries are limited to roughly {@code ratio} of
 * the request rate instead of multiplying it by maxAttempts.
 */
public final class RetryBudget {
    private final double ratio, max;
    private double tokens;

    public RetryBudget(double ratio, double initial, double max) {
        this.ratio = ratio;
        this.tokens = initial;
        this.max = max;
    }

    public synchronized void onRequest() {
        tokens = Math.min(max, tokens + ratio);
    }

    public synchronized boolean tryAcquireRetry() {
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized double tokens() {
        return tokens;
    }
}

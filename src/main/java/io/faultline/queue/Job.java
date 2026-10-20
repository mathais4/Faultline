package io.faultline.queue;

/** A claimed job. {@code attempts} counts this delivery (1 on first delivery). */
public record Job(long id, String orderKey, String payload, int attempts, int maxAttempts, String claimToken) {}

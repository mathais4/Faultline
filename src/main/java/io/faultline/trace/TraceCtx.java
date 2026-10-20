package io.faultline.trace;

/**
 * Propagated request context.
 *
 * @param traceId      shared by every span of one order (derived from the order id)
 * @param parentSpanId the span that is making the downstream call
 * @param key          the order id; the identity that fault decisions and jitter are derived from
 */
public record TraceCtx(String traceId, String parentSpanId, String key) {

    public static final String H_TRACE = "X-Faultline-Trace";
    public static final String H_PARENT = "X-Faultline-Parent";
    public static final String H_ATTEMPT = "X-Faultline-Attempt";
    public static final String H_KEY = "X-Faultline-Key";
    public static final String H_IDEMPOTENCY = "Idempotency-Key";

    public static String traceIdFor(String orderId) {
        return "tr-" + orderId;
    }

    public TraceCtx withParent(String spanId) {
        return new TraceCtx(traceId, spanId, key);
    }
}

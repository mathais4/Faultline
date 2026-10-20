package io.faultline.fault;

/** The failure modes Faultline can inject. */
public enum FaultType {
    /** Add delay. Phase BEFORE = slow service / slow network inbound; AFTER = work done, response late. */
    LATENCY,
    /** Respond with an error status (default 500) without doing the work. */
    ERROR,
    /** Close the connection without a response. Phase AFTER = work done, response lost (the nasty one). */
    DROP,
    /** Service unreachable (listener closed) for the fault window, then restarts. Durable state survives. */
    CRASH,
    /** Worker finishes a job but its acknowledgement never reaches the queue. */
    LOST_ACK,
    /** Producer publishes the same message twice. */
    DUPLICATE
}

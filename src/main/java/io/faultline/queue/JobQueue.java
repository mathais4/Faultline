package io.faultline.queue;

import java.util.List;
import java.util.Optional;

/**
 * At-least-once job queue with SQS-style visibility timeouts.
 *
 * Contract (verified by the self-check against every implementation):
 * <ul>
 *   <li>claim() hands out a job and hides it for visibilityMs; if nobody acks in time it becomes claimable again
 *       (that is the redelivery that produces duplicates).</li>
 *   <li>Each claim increments attempts and issues a fresh claim token. ack/nack with a stale token are rejected
 *       (fencing): a slow worker cannot ack a job that has since been handed to someone else.</li>
 *   <li>A job whose attempts reach maxAttempts without an ack is dead-lettered rather than retried forever.</li>
 *   <li>enqueue with a non-null dedupKey is a no-op if that key was already enqueued.</li>
 * </ul>
 * Plug your own queue into Faultline by implementing this interface.
 */
public interface JobQueue {

    /** @return the new job id, or empty if dedupKey was already present. */
    Optional<Long> enqueue(String orderKey, String dedupKey, String payload, int maxAttempts);

    List<Job> claim(String workerId, long visibilityMs, int limit);

    /** @return false if the claim token is stale (job was redelivered, finished, or dead-lettered). */
    boolean ack(Job job);

    /** Release the job for retry after retryDelayMs. Dead-letters it if attempts are exhausted. */
    boolean nack(Job job, long retryDelayMs, String error);

    QueueStats stats();

    /** Remove everything. Used between experiment trials. */
    void reset();
}

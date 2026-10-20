# Testing your own job queue with Faultline

Faultline's built-in queues (`InMemoryJobQueue`, `PostgresJobQueue`) both implement one small interface,
`io.faultline.queue.JobQueue`. Anything that implements it can be dropped into the same experiments, with the same
injected faults, and judged by the same ground truth (the inventory ledger records every real shipment).

## 1. Check that your queue meets the contract

The experiments only mean something if the queue behaves like an at-least-once queue with visibility timeouts. The
contract check is nine small tests; run it first.

```
java -jar target/faultline.jar check --queue postgres --db-url jdbc:postgresql://localhost:5432/faultline
```

| Check | Why it matters |
|---|---|
| enqueue assigns an id | basic sanity |
| dedup key drops the second enqueue | producer-side idempotency (`queue-redelivery` experiment) |
| claim hides the job for the visibility timeout | otherwise two workers share a job |
| stale ack is rejected (fencing), current ack accepted | a slow worker must not ack a job that was handed to someone else |
| acked job is never delivered again | exactly the thing at-least-once queues promise |
| nack releases the job after its retry delay | backoff is only real if the queue honours it |
| job is dead-lettered once max attempts are used | otherwise poison jobs loop forever |
| visibility lapse on the final attempt dead-letters the job | crashed workers on the last attempt must not leak jobs |
| concurrent claims never hand one job to two workers | the `SKIP LOCKED` / locking correctness test |

A failure here is a finding about your queue, before any fault is injected.

## 2. If your queue is Java: write an adapter

```java
public final class MyQueueAdapter implements JobQueue {
    private final MyQueue q;                      // your existing class
    public MyQueueAdapter(MyQueue q) { this.q = q; }

    @Override public Optional<Long> enqueue(String orderKey, String dedupKey, String payload, int maxAttempts) { ... }
    @Override public List<Job> claim(String workerId, long visibilityMs, int limit) { ... }
    @Override public boolean ack(Job job)  { ... }   // false if job.claimToken() is stale
    @Override public boolean nack(Job job, long retryDelayMs, String error) { ... }
    @Override public QueueStats stats() { ... }      // counts per state + deliveries per order key
    @Override public void reset() { ... }            // between trials
}
```

Mapping notes:

* `Job(id, orderKey, payload, attempts, maxAttempts, claimToken)`. If your queue has no fencing token, use any value
  that changes on every claim (a counter or UUID) and make `ack` compare it. If your queue cannot reject a stale
  ack at all, say so in your adapter's `ack` (always return `true`): the `queue-redelivery` experiment will then show
  what that costs you.
* `orderKey` is the business key (here the order id). It is what duplicate deliveries are counted against.
* `QueueStats(byState, deliveries, enqueues)`: states are `queued`, `running`, `done`, `dead`. See
  `PostgresJobQueue.stats()` for a worked example.

Then register it in `Main.backend(...)` next to the `memory` and `postgres` cases (about five lines) and run:

```
java -jar target/faultline.jar check --queue myqueue
java -jar target/faultline.jar run   --queue myqueue
```

## 3. If your queue is not Java

Two options, in increasing effort:

1. **Share its database.** If it is Postgres-backed, map your table to the `jobs` schema (or write the SQL
   equivalents of the five operations in `PostgresJobQueue`, which is about 120 lines) and point `--db-url` at it.
2. **Wrap it over HTTP/gRPC.** Write a `JobQueue` implementation that calls your service. Faultline's own services
   stay in-process or in containers; only the five queue operations cross to yours.

## 4. What to compare

Once your queue is plugged in, the `queue-redelivery`, `poison-jobs` and `worker-outage` experiments answer, for
*your* implementation:

* Does a lost ack produce a duplicate side effect, and does consumer idempotency actually stop it?
* How many delivery attempts does a flaky dependency need before accepted jobs stop being dead-lettered?
* After the worker tier is down, how long until the backlog clears, and how much of that is the visibility timeout
  versus raw worker capacity?

Copy a scenario in `Scenarios.java` and change the `QueueConfig` values (visibility timeout, attempts, backoff,
worker count, enqueue dedup, idempotent consumer) to match the settings you run in production.

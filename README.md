# Faultline

A distributed-system failure lab. Faultline runs a small set of cooperating services (gateway, payments,
inventory, queue worker), deliberately breaks them, and measures which resilience strategies actually kept the
system correct, rather than which ones merely look sensible in a design doc.

* **Inject**: latency, HTTP errors, dropped connections, lost responses (the work happened, the reply didn't),
  service crashes and restarts, lost queue acknowledgements, duplicate message publishes.
* **Observe**: distributed request traces, exact latency percentiles (p50/p90/p95/p99), failure rates, retry
  amplification, circuit-breaker transitions, queue redeliveries and dead letters.
* **Compare**: retry strategies (none, fixed, exponential, jitter, retry budgets), timeouts, idempotency keys,
  circuit breakers, queue visibility timeouts, attempts and backoff, enqueue dedup, idempotent consumers, worker count.
* **Judge by ground truth**: the payments and inventory services keep a durable ledger of every side effect. The
  runner audits it afterwards, so "did we charge this customer twice?" is counted, not inferred from HTTP status codes.
* **Report**: a self-contained HTML report (plus Markdown and raw JSON) with a scorecard, charts, sample traces and
  a plain-language reading of the data.

Only one third-party dependency (the PostgreSQL JDBC driver). Everything else is the JDK: `com.sun.net.httpserver`,
`java.net.http`, virtual threads, `java.sql`. Requires Java 21.

---

## Quick start

### 1. No Docker, no Maven, no database (in-memory queue)

```bash
scripts/run.sh list                      # what experiments exist
scripts/run.sh run --quick               # every experiment, 1 trial per variant (~5 min)
scripts/run.sh run                       # 3 trials per variant, mean and spread in the report
scripts/run.sh run --only lost-response  # one experiment
open out/<timestamp>/report.html
```

### 2. With Maven

```bash
mvn package                              # compiles, runs the tests, builds target/faultline.jar
java -jar target/faultline.jar run --only flaky-payments,lost-response
```

### 3. Real containers and a real PostgreSQL queue

```bash
docker compose up --build --abort-on-container-exit lab     # postgres + payments + inventory + gateway + worker + lab
open out/report.html
```

Every service is its own container with its own network address, and the job queue lives in PostgreSQL
(`FOR UPDATE SKIP LOCKED`, fencing tokens, database-clock visibility timeouts). A single-container variant, where the
services share one JVM but still use the real PostgreSQL, is `docker compose --profile single run --rm lab-single`.

Without Docker but with PostgreSQL installed: `eval "$(scripts/local-postgres.sh)"` starts a throwaway server and
prints the `FAULTLINE_DB_*` variables; then `java -jar target/faultline.jar run`.

### Reading the result

`report.html` opens on a **scorecard**: for each experiment, which variants *worked* and which *failed*, and why.
A variant "worked" only if **every trial met every check** the experiment declares, so a strategy that is fast but
double-charges customers does not pass. The limits (for example "p99 under 1500 ms", "0 customers charged twice")
are printed next to the numbers and are plain constructor arguments in `Scenarios.java`; change them to match your
own targets.

---

## The experiments

| Id | Question | Varies |
|---|---|---|
| `flaky-payments` | Which retry strategy rescues orders when 30% of payment calls fail? | none / fixed / exponential / jitter / jitter + retry budget |
| `lost-response` | If a charge succeeds but the reply is lost, does retrying charge the customer twice? | no retry / blind retry / retry + idempotency keys |
| `payments-outage` | When payments hangs for 3 s, do retries help, hurt, or prolong the outage? | no retry / retry / + circuit breaker / + retry budget |
| `timeouts` | How tight can the per-attempt timeout be before it hurts more than it helps? | 30 ms / 150 ms / 600 ms / 3000 ms |
| `queue-redelivery` | Which queue configuration keeps a shipment from happening twice? | enqueue dedup, idempotent consumer, visibility timeout |
| `poison-jobs` | How many delivery attempts does a job need when its dependency is flaky? | 1 / 3 / 6 / 10 attempts, with and without backoff |
| `worker-outage` | After the worker tier is down for 3 s, how long until the backlog clears? | visibility timeout x worker count |

`scripts/run.sh list` prints each experiment's variants.

### Fault types

| Fault | What it does |
|---|---|
| `LATENCY` (before) | The service is slow to start working. |
| `LATENCY` (after) | The work is done, then the reply is late. The client may already have timed out and retried. |
| `ERROR` | HTTP error (default 500) without doing the work. |
| `DROP` (before / after) | Connection closed with no reply, before the work or after it (the dangerous one). |
| `CRASH` | Service refuses connections for a window, then comes back; durable state survives. A worker that crashes mid-job abandons it un-acked. |
| `LOST_ACK` | The worker finishes the job but its acknowledgement never reaches the queue. |
| `DUPLICATE` | The producer publishes the same message twice. |

Faults carry a probability, an optional time window relative to the start of the load, and a route.

---

## How it works

```
                      +-----------+   POST /order     +----------------------+
   load generator --> |  gateway  | ----------------> |  ResilientClient     |
   (Runner)           |           |    timeout, retry,|  per-dependency      |
                      |           |    backoff+jitter,|  breaker + budget    |
                      |           |    idempotency key|                      |
                      +-----+-----+                   +---+--------------+---+
                            |                             |              |
                   enqueue  |                    /reserve v      /charge v
                            v                      +-----------+   +-----------+
                      +-----------+                | inventory |   | payments  |
                      | JobQueue  |                |  ledger   |   |  ledger   |
                      | memory or |                +-----+-----+   +-----------+
                      | PostgreSQL|                      ^
                      +-----+-----+      /commit          |
                            | claim                       |
                            v                             |
                      +-----------+-------------------------+
                      |  worker   |  visibility timeout, ack/nack, fencing
                      +-----------+
```

* **Services** (`service/`): small HTTP servers on the JDK's own server. Each one applies the fault injector to its
  routes, bounds its own concurrency (so a hung dependency exhausts capacity the way a real one does; callers that
  time out do not cancel work already queued server-side), records spans, and exposes `/_faultline/*` control and
  metrics endpoints. Their ledgers survive simulated crashes.
* **Fault injection** (`fault/`): decisions are a pure function of `(seed, fault id, order id, attempt)`. Every variant
  of an experiment therefore faces the *same* faults on the *same* orders; differences between variants are
  differences in strategy, not luck of the draw. Each experiment prints a fault fingerprint so you can check this.
  What stays non-deterministic is real-world timing (thread scheduling), which is why each variant runs several
  trials and the report shows mean and spread.
* **Resilience layer** (`resilience/`): `ResilientClient` with per-attempt timeouts, retry strategies (none, fixed,
  exponential, exponential with full jitter), a count-based sliding-window circuit breaker (closed / open / half-open), a retry
  budget that caps retries as a fraction of traffic, and optional idempotency keys.
* **Queue** (`queue/`): an at-least-once queue with SQS-style visibility timeouts and fencing tokens, in two
  implementations behind one interface. `QueueContract` is a nine-check conformance suite run against both.
* **Tracing** (`trace/`): trace and span ids travel in headers across every hop, including through the queue payload,
  so one order's gateway, payments, inventory, enqueue and worker spans form a single trace. The report shows
  sample traces (a double-charged customer, an order that shipped twice, one that was accepted but never shipped, ...).
* **Runner and report** (`lab/`): runs each variant for several trials against freshly reset services, scores them
  from the ledgers, and writes `results.json`, `report.html`, `report.md`. `faultline report results.json`
  regenerates the report from saved results.

### Metrics worth knowing

| Metric | Meaning |
|---|---|
| Success | Orders the customer saw accepted / orders sent. |
| Charged twice | Customers the payments ledger charged more than once. |
| Charged, order failed | Customers charged although the order was reported as failed. |
| Shipped twice | Orders the inventory ledger shipped more than once. |
| Accepted, never shipped | Orders the customer was told were accepted that never shipped (dead-lettered or lost). |
| Recovery after outage | Time after the fault window ends until the system is healthy again: order success back above 95% *and* the last late shipment (more than 1 s after acceptance) has shipped. |
| Accepted-to-shipped p99 | How long the asynchronous half takes, measured from the shipment ledger's own timestamps. |
| Payments attempts per call | Retry amplification: how much extra load the strategy puts on a struggling dependency. |

---

## What the built-in experiments found

From a full run (3 trials per variant, in-memory queue, 2 CPUs; the complete report is in
[docs/sample-report/](docs/sample-report/report.html)). Numbers will move a little between machines; the ordering
should not.

* **Retries rescue a flaky dependency, but the strategy matters.** With 30% of payment calls failing, no retry
  succeeded 69% of the time. Fixed-delay x3 reached 97.8% but missed a 98% target in one trial; exponential backoff
  reached 99.2%, and adding jitter cut p99 from 368 ms to 186 ms. A 20% retry budget protected the dependency but
  capped success at 84%.
* **Retrying without idempotency keys double-charges.** With replies lost after the work was done, blind retries
  charged about 100 of 400 customers twice. Idempotency keys brought that to zero at identical success and latency.
  Not retrying left ~98 customers charged but shown an error.
* **A hung dependency: retries hurt latency, a breaker repairs it.** Retries pushed p99 to 1.6 s and sent 1.7 payment
  attempts per call into a service that could not answer; adding a circuit breaker returned p99 to 0.56 s and cut load
  to 0.73 attempts per call. No variant could succeed *during* the hang, and recovery afterwards was about the same
  for all, so in this setup retries did not measurably prolong the outage.
* **Timeouts:** 30 ms collapsed (38% success, 2.6 attempts per call). 150 ms was unreliable (98.4%, passing one trial
  of three); 600 ms and 3 s both worked.
* **Queues:** lost acks and slow jobs redeliver. Enqueue dedup alone removed only about a quarter of double
  shipments (87 to 62); an idempotent consumer removed all of them. Even then a job occasionally exhausts its
  attempts un-acked and is dead-lettered *although the shipment happened*, so a dead-letter count is not the same as
  lost work. A 3 s visibility timeout avoided that but was slower to clear lost acks (11.5 s vs 8.5 s drain).
* **Job attempts:** with 35% commit failures, 1 attempt lost 113 of 300 accepted orders, 3 lost about 11, 6 lost
  about 1 in one trial, and 10 lost none. Backoff made no difference to loss, only to retry pacing.
* **Worker outage:** with 2 workers the backlog took about 8 s to clear no matter the visibility timeout (capacity
  bound); with 8 workers recovery took 0.6 s at 500 ms visibility and 2.1 s at 5 s, the cost of jobs stranded by the
  crash.

Several of these thresholds (for example the 10 s queue drain target) were chosen after a first run showed what was
achievable. They are constructor arguments in `Scenarios.java`; change them to your own targets and re-run.

## Verification status

* **Verified here:** 26 unit and integration tests pass; every experiment ran end to end twice with the same
  verdicts; the HTML report was opened and inspected in a browser; the queue's exact SQL (schema, enqueue/dedup,
  claim, fencing, nack, dead-lettering, `SKIP LOCKED` under concurrency, concurrent schema creation) passes
  `scripts/verify-queue-sql.sh` against a real PostgreSQL 16.
* **Not verified here:** the sandbox had no Docker daemon and no access to Maven Central, so `docker compose`,
  `mvn package`, and the Java `PostgresJobQueue` talking to PostgreSQL through the JDBC driver were not run. Run
  `docker compose up --build --abort-on-container-exit lab` and `java -jar target/faultline.jar check --queue postgres`
  first; the second is the contract suite against your database.

## Adding an experiment

Append to `Scenarios.all()`; nothing else changes:

```java
static Experiment slowInventory() {
    return new Experiment("slow-inventory", "Hedging against slow reads",
        "Does a 2-attempt policy beat waiting?",                    // the question the report leads with
        "20% of reserve calls take 1s.",                            // what is injected, in words
        300, 50, 808,                                               // orders, orders/sec, seed
        tuning(),                                                   // per-service capacity (optional)
        List.of(FaultPlan.latency("inventory", "inv-slow", "/reserve", 0.20, 1000, BEFORE, 0, 0)),
        List.of(
            v("wait",  "Wait it out", "One attempt, 3 s timeout.", policy(3000, 1, RetryStrategy.NONE, 0, 0, null, null, true)),
            v("retry", "Retry at 200ms", "Two attempts.",           policy(200, 2, RetryStrategy.FIXED, 10, 10, null, null, true))),
        new Slo(0.99, 800, 0, -1, -1, -1, -1, -1),                  // success >= 99%, p99 <= 800ms, no double charges
        8000);                                                      // max ms to wait for the queue to drain
}
```

Use `q(...)` variants with `qc(visibilityMs, maxAttempts, backoff, base, cap, workers, enqueueDedup, idempotentConsumer)`
to compare queue settings. An `Slo` field of `-1` disables that check.

To test your own job queue, see [docs/job-queue-integration.md](docs/job-queue-integration.md).

---

## Testing

```bash
scripts/test.sh              # 26 JUnit tests (plain javac + JUnit 4, no Maven needed)
mvn test                     # same tests through Maven
scripts/verify-queue-sql.sh  # runs the queue's exact SQL against a real PostgreSQL via psql
```

The integration tests start real services on real sockets and assert on outcomes: blind retries must double-charge
someone and idempotency keys must prevent it; un-acked jobs must double-ship unless the consumer is idempotent; a
worker crash must strand in-flight jobs until the visibility timeout expires.

## Project layout

```
src/main/java/io/faultline/
  Main.java            CLI: list | run | report | check | node
  fault/               fault types, specs, deterministic injector
  resilience/          ResilientClient, retry strategies, circuit breaker, retry budget
  service/             gateway, payments, inventory, worker, ledgers, idempotency store
  queue/               JobQueue interface, in-memory + PostgreSQL implementations, contract checks
  trace/               trace context, spans, span store
  lab/                 experiments, scenarios, runner, HTML/Markdown report writer
  core/                JSON, statistics, metrics, hashing (no external libraries)
src/main/resources/io/faultline/schema.sql   queue schema
docs/                  integration guide
scripts/               run / test / local postgres / SQL verification
Dockerfile, docker-compose.yml
```

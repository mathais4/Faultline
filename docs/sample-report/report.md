# Faultline report

Run 2026-10-18T15:46:49.638823651Z | queue: in-memory | 3 trial(s) per variant | Java 21.0.12.1

A variant **worked** only if every trial met every check in the experiment's SLO.

## Retry strategies against a flaky dependency

**Which retry strategy rescues orders when 30% of payment calls fail?**

Payments answers HTTP 503 to 30% of charge attempts (re-rolled on every attempt) before doing any work, so retrying is safe and the only question is how to retry. Each variant gets the same orders and the same failures.

Faults:
- payments /charge: 30% of requests answers HTTP 503 without doing the work, for the whole run

SLO: success ≥ 98.0%, p99 ≤ 1500ms, charged twice ≤ 0, shipped twice ≤ 0, lost orders ≤ 0 | planned fault fingerprint `d32a2a42e5bd`

| Variant | Verdict | Success | p50 | p95 | p99 | Charged twice | Shipped twice | Accepted, never shipped | Payments attempts per call |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| No retry | FAILED | 69.3% | 19 ms | 28 ms | 35 ms | 0 | 0 | 0 | 1.00x |
| Fixed delay x3 | PARTIAL | 97.8% | 20 ms | 219 ms | 224 ms | 0 | 0 | 0 | 1.39x |
| Exponential x4 | WORKED | 99.2% | 19 ms | 171 ms | 368 ms | 0 | 0 | 0 | 1.41x |
| Exp + jitter x4 | WORKED | 99.2% | 19 ms | 89 ms | 186 ms | 0 | 0 | 0 | 1.41x |
| Exp + jitter x4 + 20% retry budget | FAILED | 84.0% | 17 ms | 56 ms | 87 ms | 0 | 0 | 0 | 1.21x |

- **No retry** missed: success rate 70.5% < 98.0%; success rate 69.5% < 98.0%; success rate 67.8% < 98.0%
- **Fixed delay x3** missed: success rate 96.5% < 98.0%
- **Exp + jitter x4 + 20% retry budget** missed: success rate 84.0% < 98.0%; success rate 85.5% < 98.0%; success rate 82.5% < 98.0%
- Met the SLO in every trial: Exponential x4; Exp + jitter x4.
- Compared with No retry, Fixed delay x3: success rose from 69.3% to 97.8%; p99 latency went from 35ms to 224ms; sent 1.39 payment attempts per call.
- Compared with No retry, Exponential x4: success rose from 69.3% to 99.2%; p99 latency went from 35ms to 368ms; sent 1.41 payment attempts per call.
- Compared with No retry, Exp + jitter x4: success rose from 69.3% to 99.2%; p99 latency went from 35ms to 186ms; sent 1.41 payment attempts per call.
- Compared with No retry, Exp + jitter x4 + 20% retry budget: success rose from 69.3% to 84.0%; p99 latency went from 35ms to 87ms.
- Met the SLO in some trials but not others (not reliable): Fixed delay x3 (2 of 3 trials).
- Failed every trial: No retry; Exp + jitter x4 + 20% retry budget.

## Retries and double charges

**If a charge succeeds but the response is lost, does retrying charge the customer twice?**

15% of charge calls do the work and then drop the connection; another 10% do the work but answer after the client has given up (700ms against a 400ms timeout). The client cannot tell these apart from a failure that did nothing. The payments ledger is the ground truth for how many times each customer was actually charged.

Faults:
- payments /charge: 15% of requests does the work, then closes the connection without responding, for the whole run
- payments /charge: 10% of requests adds 700ms after doing the work (response is late), for the whole run

SLO: success ≥ 97.0%, charged twice ≤ 0, shipped twice ≤ 0, lost orders ≤ 0 | planned fault fingerprint `3987148ea526`

| Variant | Verdict | Success | p50 | p95 | p99 | Charged twice | Charged, order failed | Shipped twice | Accepted, never shipped | Payments attempts per call |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| No retry | FAILED | 75.4% | 58 ms | 411 ms | 415 ms | 0 | 98 | 0 | 0 | 1.00x |
| Retry x3 (no idempotency) | FAILED | 99.0% | 60 ms | 483 ms | 750 ms | 98 | 4 | 0 | 0 | 1.31x |
| Retry x3 + idempotency keys | WORKED | 99.0% | 60 ms | 485 ms | 746 ms | 0 | 4 | 0 | 0 | 1.31x |

- **No retry** missed: success rate 74.3% < 97.0%; success rate 74.5% < 97.0%; success rate 77.5% < 97.0%
- **Retry x3 (no idempotency)** missed: 103 customers charged twice; 102 customers charged twice; 90 customers charged twice
- Met the SLO in every trial: Retry x3 + idempotency keys.
- Compared with No retry, Retry x3 (no idempotency): success rose from 75.4% to 99.0%; customers charged twice went from 0 to 98; customers charged but shown a failure went from 98 to 4; p99 latency went from 415ms to 750ms; sent 1.31 payment attempts per call.
- Compared with No retry, Retry x3 + idempotency keys: success rose from 75.4% to 99.0%; customers charged but shown a failure went from 98 to 4; p99 latency went from 415ms to 746ms; sent 1.31 payment attempts per call.
- Failed every trial: No retry; Retry x3 (no idempotency).

## Circuit breakers during a hanging dependency

**When payments hangs for 3 seconds, do retries help, hurt, or prolong the outage?**

From t+2s to t+5s every payments call stalls for 3 seconds. Payments can only hold 64 requests at once, and a client timeout does not cancel the work already sitting on the server, so abandoned requests keep occupying capacity after callers have given up. Idempotency keys are on everywhere so correctness does not muddy the comparison.

Faults:
- payments /charge: adds 3000ms before doing the work, from t+2000ms to t+5000ms

SLO: p99 ≤ 1500ms, charged twice ≤ 0, recovery ≤ 1500ms | planned fault fingerprint `6b8da13759f3`

| Variant | Verdict | Success | p50 | p95 | p99 | Charged twice | Charged, order failed | Payments attempts per call | Success during outage | Recovery after outage | Accepted-to-shipped p99 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| No retry | WORKED | 66.7% | 31 ms | 528 ms | 531 ms | 0 | 112 | 1.00x | 0.2% | 0 ms | 116 ms |
| Retry x3 | FAILED | 69.7% | 20 ms | 1,165 ms | 1,606 ms | 0 | 80 | 1.68x | 11.1% | 250 ms | 209 ms |
| Retry x3 + circuit breaker | WORKED | 65.1% | 22 ms | 528 ms | 561 ms | 0 | 59 | 0.73x | 0.0% | 250 ms | 19 ms |
| Retry x3 + breaker + retry budget | WORKED | 65.2% | 25 ms | 513 ms | 558 ms | 0 | 58 | 0.73x | 0.0% | 250 ms | 18 ms |

- **Retry x3** missed: p99 1607ms > 1500ms; p99 1598ms > 1500ms; p99 1614ms > 1500ms
- Met the SLO in every trial: No retry; Retry x3 + circuit breaker; Retry x3 + breaker + retry budget.
- Compared with No retry, Retry x3: success rose from 66.7% to 69.7%; customers charged but shown a failure went from 112 to 80; p99 latency went from 531ms to 1,606ms; sent 1.68 payment attempts per call; recovery after the outage went from 0ms to 250ms.
- Compared with No retry, Retry x3 + circuit breaker: customers charged but shown a failure went from 112 to 59; recovery after the outage went from 0ms to 250ms.
- Compared with No retry, Retry x3 + breaker + retry budget: customers charged but shown a failure went from 112 to 58; recovery after the outage went from 0ms to 250ms.
- Failed every trial: Retry x3.

## Choosing a timeout

**How tight can the per-attempt timeout be before it hurts more than it helps?**

Payments normally answers in 20-60ms, but 25% of calls are slow by an extra 450ms. A short timeout abandons slow calls early and retries; a long one waits them out. Every variant retries up to 3 times with idempotency keys.

Faults:
- payments /charge: 25% of requests adds 450ms before doing the work, for the whole run

SLO: success ≥ 99.0%, p99 ≤ 700ms, charged twice ≤ 0, shipped twice ≤ 0, lost orders ≤ 0 | planned fault fingerprint `b6391a4fdb44`

| Variant | Verdict | Success | p50 | p95 | p99 | Charged twice | Charged, order failed | Shipped twice | Accepted, never shipped | Payments attempts per call |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Timeout 30ms | FAILED | 38.2% | 123 ms | 154 ms | 168 ms | 0 | 185 | 0 | 0 | 2.57x |
| Timeout 150ms | PARTIAL | 98.4% | 96 ms | 376 ms | 480 ms | 0 | 5 | 0 | 0 | 1.32x |
| Timeout 600ms | WORKED | 100.0% | 61 ms | 514 ms | 530 ms | 0 | 0 | 0 | 0 | 1.00x |
| Timeout 3000ms | WORKED | 100.0% | 59 ms | 513 ms | 520 ms | 0 | 0 | 0 | 0 | 1.00x |

- **Timeout 30ms** missed: success rate 36.7% < 99.0%; success rate 42.0% < 99.0%; success rate 36.0% < 99.0%
- **Timeout 150ms** missed: success rate 97.7% < 99.0%; success rate 98.7% < 99.0%
- Met the SLO in every trial: Timeout 600ms; Timeout 3000ms.
- Compared with Timeout 30ms, Timeout 150ms: success rose from 38.2% to 98.4%; customers charged but shown a failure went from 185 to 5; p99 latency went from 168ms to 480ms; sent 1.32 payment attempts per call.
- Compared with Timeout 30ms, Timeout 600ms: success rose from 38.2% to 100.0%; customers charged but shown a failure went from 185 to 0; p99 latency went from 168ms to 530ms.
- Compared with Timeout 30ms, Timeout 3000ms: success rose from 38.2% to 100.0%; customers charged but shown a failure went from 185 to 0; p99 latency went from 168ms to 520ms.
- Met the SLO in some trials but not others (not reliable): Timeout 150ms (1 of 3 trials).
- Failed every trial: Timeout 30ms.

## Queue settings and duplicate shipments

**Which queue configuration keeps a shipment from happening twice?**

Three things go wrong at once: 15% of acknowledgements are lost, 10% of jobs run slower (1.2s) than a short visibility timeout, and 10% of messages are published twice. At-least-once delivery means all three lead to redelivery. The inventory ledger counts real shipments.

Faults:
- worker ack: 15% of requests finishes the job but its acknowledgement never reaches the queue, for the whole run
- worker process: 10% of requests adds 1200ms before doing the work, for the whole run
- gateway enqueue: 10% of requests publishes the message twice, for the whole run

SLO: shipped twice ≤ 0, lost orders ≤ 0, dead letters ≤ 0, queue drains in ≤ 10000ms | planned fault fingerprint `d8bae65c82fa`

| Variant | Verdict | Success | p50 | p95 | p99 | Shipped twice | Accepted, never shipped | Dead-lettered | Queue drain | Accepted-to-shipped p99 | Redeliveries |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Baseline | FAILED | 100.0% | 17 ms | 22 ms | 23 ms | 87 | 0 | 0 | 9,528 ms | 7,677 ms | 85 |
| + enqueue dedup | FAILED | 100.0% | 17 ms | 22 ms | 26 ms | 62 | 0 | 0 | 8,537 ms | 6,810 ms | 76 |
| + idempotent consumer | PARTIAL | 100.0% | 17 ms | 22 ms | 25 ms | 0 | 0 | 0 | 9,681 ms | 7,733 ms | 85 |
| + dedup + idempotent consumer | PARTIAL | 100.0% | 17 ms | 22 ms | 24 ms | 0 | 0 | 0 | 8,536 ms | 6,817 ms | 76 |
| Both, 3s visibility | PARTIAL | 100.0% | 17 ms | 22 ms | 24 ms | 0 | 0 | 0 | 11,492 ms | 5,858 ms | 55 |

- **Baseline** missed: 93 orders shipped twice; 92 orders shipped twice; 76 orders shipped twice; 1 jobs dead-lettered
- **+ enqueue dedup** missed: 65 orders shipped twice; 67 orders shipped twice; 55 orders shipped twice; 1 jobs dead-lettered
- **+ idempotent consumer** missed: 1 jobs dead-lettered; queue drained in 10140ms (limit 10000ms)
- **+ dedup + idempotent consumer** missed: 1 jobs dead-lettered
- **Both, 3s visibility** missed: queue drained in 13540ms (limit 10000ms); queue drained in 11234ms (limit 10000ms)
- No variant met the SLO in every trial.
- Compared with Baseline, + enqueue dedup: orders shipped twice went from 87 to 62.
- Compared with Baseline, + idempotent consumer: orders shipped twice went from 87 to 0.
- Compared with Baseline, + dedup + idempotent consumer: orders shipped twice went from 87 to 0.
- Compared with Baseline, Both, 3s visibility: orders shipped twice went from 87 to 0; queue drain time went from 9,528ms to 11,492ms.
- Met the SLO in some trials but not others (not reliable): + idempotent consumer (2 of 3 trials); + dedup + idempotent consumer (2 of 3 trials); Both, 3s visibility (1 of 3 trials).
- Failed every trial: Baseline; + enqueue dedup.

## Dead letters and job retries

**How many delivery attempts does a job need when its dependency is flaky?**

Inventory fails 35% of shipment commits (re-rolled on every delivery). The gateway has already told the customer 'accepted', so a job that exhausts its attempts is an order the customer was promised and never receives.

Faults:
- inventory /commit: 35% of requests answers HTTP 503 without doing the work, for the whole run

SLO: shipped twice ≤ 0, lost orders ≤ 0, dead letters ≤ 0, queue drains in ≤ 8000ms | planned fault fingerprint `d8240bf679f8`

| Variant | Verdict | Success | p50 | p95 | p99 | Shipped twice | Accepted, never shipped | Dead-lettered | Queue drain | Accepted-to-shipped p99 | Redeliveries |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 attempt | FAILED | 100.0% | 17 ms | 22 ms | 25 ms | 0 | 114 | 114 | 51 ms | 19 ms | 0 |
| 3 attempts, backoff | FAILED | 100.0% | 17 ms | 21 ms | 24 ms | 0 | 11 | 11 | 152 ms | 277 ms | 152 |
| 6 attempts, backoff | PARTIAL | 100.0% | 16 ms | 21 ms | 23 ms | 0 | 0 | 0 | 387 ms | 797 ms | 171 |
| 6 attempts, no backoff | PARTIAL | 100.0% | 16 ms | 21 ms | 23 ms | 0 | 0 | 0 | 51 ms | 19 ms | 171 |
| 10 attempts, backoff | WORKED | 100.0% | 17 ms | 21 ms | 24 ms | 0 | 0 | 0 | 387 ms | 795 ms | 171 |

- **1 attempt** missed: 113 accepted orders never shipped; 113 jobs dead-lettered; 114 accepted orders never shipped; 114 jobs dead-lettered
- **3 attempts, backoff** missed: 12 accepted orders never shipped; 12 jobs dead-lettered; 9 accepted orders never shipped; 9 jobs dead-lettered; 13 accepted orders never shipped; 13 jobs dead-lettered
- **6 attempts, backoff** missed: 1 accepted orders never shipped; 1 jobs dead-lettered
- **6 attempts, no backoff** missed: 1 accepted orders never shipped; 1 jobs dead-lettered
- Met the SLO in every trial: 10 attempts, backoff.
- Compared with 1 attempt, 3 attempts, backoff: accepted-but-never-shipped orders went from 114 to 11; dead-lettered jobs went from 114 to 11.
- Compared with 1 attempt, 6 attempts, backoff: accepted-but-never-shipped orders went from 114 to 0; dead-lettered jobs went from 114 to 0.
- Compared with 1 attempt, 6 attempts, no backoff: accepted-but-never-shipped orders went from 114 to 0; dead-lettered jobs went from 114 to 0.
- Compared with 1 attempt, 10 attempts, backoff: accepted-but-never-shipped orders went from 114 to 0; dead-lettered jobs went from 114 to 0.
- Met the SLO in some trials but not others (not reliable): 6 attempts, backoff (2 of 3 trials); 6 attempts, no backoff (2 of 3 trials).
- Failed every trial: 1 attempt; 3 attempts, backoff.

## Queue recovery after a worker outage

**After the whole worker tier is down for 3 seconds, how long until the backlog clears?**

Each shipment job takes about 150ms, and orders arrive at 10 per second. The worker tier is down from t+2s to t+5s while orders keep arriving. Work in flight at the moment of the crash is abandoned un-acknowledged, so it only comes back when its visibility timeout expires, and the backlog that built up during the outage has to be worked off by however many workers there are. Recovery means the last order shipped late (more than 1s after the customer was told 'accepted') has shipped. Compares visibility timeout and worker count.

Faults:
- worker process: adds 150ms before doing the work, for the whole run
- worker *: is down (connection refused), from t+2000ms to t+5000ms

SLO: shipped twice ≤ 0, lost orders ≤ 0, dead letters ≤ 0, recovery ≤ 4000ms | planned fault fingerprint `eecf78710501`

| Variant | Verdict | Success | p50 | p95 | p99 | Shipped twice | Accepted, never shipped | Dead-lettered | Success during outage | Recovery after outage | Accepted-to-shipped p99 | Redeliveries |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 500ms visibility, 2 workers | FAILED | 100.0% | 17 ms | 22 ms | 24 ms | 0 | 0 | 0 | 100.0% | 7,980 ms | 3,200 ms | 1 |
| 5s visibility, 2 workers | FAILED | 100.0% | 17 ms | 22 ms | 25 ms | 0 | 0 | 0 | 100.0% | 7,959 ms | 3,141 ms | 1 |
| 500ms visibility, 8 workers | WORKED | 100.0% | 17 ms | 21 ms | 24 ms | 0 | 0 | 0 | 100.0% | 639 ms | 3,149 ms | 1 |
| 5s visibility, 8 workers | WORKED | 100.0% | 17 ms | 22 ms | 24 ms | 0 | 0 | 0 | 100.0% | 2,090 ms | 3,151 ms | 1 |

- **500ms visibility, 2 workers** missed: recovery took 7988ms (limit 4000ms); recovery took 7954ms (limit 4000ms); recovery took 7999ms (limit 4000ms)
- **5s visibility, 2 workers** missed: recovery took 7960ms (limit 4000ms); recovery took 7951ms (limit 4000ms); recovery took 7965ms (limit 4000ms)
- Met the SLO in every trial: 500ms visibility, 8 workers; 5s visibility, 8 workers.
- Compared with 500ms visibility, 2 workers, 500ms visibility, 8 workers: recovery after the outage went from 7,980ms to 639ms.
- Compared with 500ms visibility, 2 workers, 5s visibility, 8 workers: recovery after the outage went from 7,980ms to 2,090ms.
- Failed every trial: 500ms visibility, 2 workers; 5s visibility, 2 workers.


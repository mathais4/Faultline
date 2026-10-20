package io.faultline.lab;

import static io.faultline.fault.FaultSpec.Phase.AFTER;
import static io.faultline.fault.FaultSpec.Phase.BEFORE;

import io.faultline.lab.Experiment.FaultPlan;
import io.faultline.lab.Experiment.Slo;
import io.faultline.lab.Experiment.Variant;
import io.faultline.queue.QueueConfig;
import io.faultline.resilience.CircuitBreaker;
import io.faultline.resilience.ResiliencePolicy;
import io.faultline.resilience.RetryStrategy;
import io.faultline.service.ServiceNode.Tuning;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in experiment catalogue. Each scenario asks one question and varies one family of strategies.
 * Add your own by appending to {@link #all()}; nothing else needs to change.
 */
public final class Scenarios {
    private Scenarios() {}

    private static final CircuitBreaker.Config BREAKER = new CircuitBreaker.Config(20, 10, 0.5, 500, 3);
    private static final ResiliencePolicy.Budget BUDGET = new ResiliencePolicy.Budget(0.2, 5, 20);

    public static List<Experiment> all() {
        return List.of(flakyPayments(), lostResponse(), paymentsOutage(), timeouts(), queueRedelivery(),
                poisonJobs(), workerOutage());
    }

    public static Experiment byId(String id) {
        return all().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown experiment: " + id));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static ResiliencePolicy policy(long timeoutMs, int attempts, RetryStrategy retry, long baseMs, long capMs,
                                           CircuitBreaker.Config breaker, ResiliencePolicy.Budget budget,
                                           boolean idem) {
        return new ResiliencePolicy(timeoutMs, attempts, retry, baseMs, capMs, 3000, breaker, budget, idem);
    }

    private static Variant v(String id, String label, String desc, ResiliencePolicy p) {
        return new Variant(id, label, desc, p, QueueConfig.DEFAULT);
    }

    private static Variant q(String id, String label, String desc, QueueConfig c) {
        return new Variant(id, label, desc, policy(400, 1, RetryStrategy.NONE, 0, 0, null, null, false), c);
    }

    private static Map<String, Tuning> tuning(Object... pairs) {
        Map<String, Tuning> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Tuning) pairs[i + 1]);
        return m;
    }

    private static QueueConfig qc(long visMs, int attempts, RetryStrategy backoff, long base, long cap, int workers,
                                  boolean dedup, boolean idem) {
        return new QueueConfig(visMs, attempts, backoff, base, cap, workers, dedup, idem);
    }

    // ------------------------------------------------------------------------------------------------ scenarios

    static Experiment flakyPayments() {
        return new Experiment("flaky-payments", "Retry strategies against a flaky dependency",
                "Which retry strategy rescues orders when 30% of payment calls fail?",
                "Payments answers HTTP 503 to 30% of charge attempts (re-rolled on every attempt) before doing any "
                        + "work, so retrying is safe and the only question is how to retry. Each variant gets the "
                        + "same orders and the same failures.",
                400, 80, 101, tuning(), List.of(FaultPlan.error("payments", "pay-503", "/charge", 0.30, 503)),
                List.of(
                        v("none", "No retry", "One attempt, then give up.",
                                policy(400, 1, RetryStrategy.NONE, 0, 0, null, null, false)),
                        v("fixed", "Fixed delay x3", "Retry up to 3 times, 100ms apart.",
                                policy(400, 3, RetryStrategy.FIXED, 100, 100, null, null, false)),
                        v("exp", "Exponential x4", "Up to 4 attempts, 50ms doubling to 800ms.",
                                policy(400, 4, RetryStrategy.EXPONENTIAL, 50, 800, null, null, false)),
                        v("jitter", "Exp + jitter x4", "Same, with full jitter.",
                                policy(400, 4, RetryStrategy.EXPONENTIAL_JITTER, 50, 800, null, null, false)),
                        v("jitter-budget", "Exp + jitter x4 + 20% retry budget",
                                "As above, but retries are capped at about 20% of call volume.",
                                policy(400, 4, RetryStrategy.EXPONENTIAL_JITTER, 50, 800, null, BUDGET, false))),
                new Slo(0.98, 1500, 0, 0, 0, -1, -1, -1), 8000);
    }

    static Experiment lostResponse() {
        return new Experiment("lost-response", "Retries and double charges",
                "If a charge succeeds but the response is lost, does retrying charge the customer twice?",
                "15% of charge calls do the work and then drop the connection; another 10% do the work but "
                        + "answer after the client has given up (700ms against a 400ms timeout). The client cannot "
                        + "tell these apart from a failure that did nothing. The payments ledger is the ground "
                        + "truth for how many times each customer was actually charged.",
                400, 80, 202, tuning(),
                List.of(FaultPlan.drop("payments", "pay-drop-after", "/charge", 0.15, AFTER),
                        FaultPlan.latency("payments", "pay-slow-reply", "/charge", 0.10, 700, AFTER, 0, 0)),
                List.of(
                        v("none", "No retry", "Failed means failed; customer may be charged anyway.",
                                policy(400, 1, RetryStrategy.NONE, 0, 0, null, null, false)),
                        v("retry", "Retry x3 (no idempotency)", "Retry blindly after timeouts and dropped connections.",
                                policy(400, 3, RetryStrategy.EXPONENTIAL_JITTER, 50, 800, null, null, false)),
                        v("retry-idem", "Retry x3 + idempotency keys",
                                "Same retries, but the charge request carries an Idempotency-Key.",
                                policy(400, 3, RetryStrategy.EXPONENTIAL_JITTER, 50, 800, null, null, true))),
                new Slo(0.97, -1, 0, 0, 0, -1, -1, -1), 8000);
    }

    static Experiment paymentsOutage() {
        Tuning payments = new Tuning(5, 64, 64, 500);
        return new Experiment("payments-outage", "Circuit breakers during a hanging dependency",
                "When payments hangs for 3 seconds, do retries help, hurt, or prolong the outage?",
                "From t+2s to t+5s every payments call stalls for 3 seconds. Payments can only hold 64 requests at "
                        + "once, and a client timeout does not cancel the work already sitting on the server, so "
                        + "abandoned requests keep occupying capacity after callers have given up. Idempotency keys "
                        + "are on everywhere so correctness does not muddy the comparison.",
                900, 100, 303,
                tuning("payments", payments, "gateway", new Tuning(5, 400, 512, 1000)),
                List.of(FaultPlan.latency("payments", "pay-hang", "/charge", 1.0, 3000, BEFORE, 2000, 5000)),
                List.of(
                        v("none", "No retry", "Timeout 500ms, single attempt.",
                                policy(500, 1, RetryStrategy.NONE, 0, 0, null, null, true)),
                        v("retry", "Retry x3", "Timeout 500ms, up to 3 attempts with jittered backoff.",
                                policy(500, 3, RetryStrategy.EXPONENTIAL_JITTER, 50, 400, null, null, true)),
                        v("retry-breaker", "Retry x3 + circuit breaker",
                                "Same, and the breaker fails fast once half the recent calls fail.",
                                policy(500, 3, RetryStrategy.EXPONENTIAL_JITTER, 50, 400, BREAKER, null, true)),
                        v("retry-breaker-budget", "Retry x3 + breaker + retry budget",
                                "Breaker plus a 20% retry budget.",
                                policy(500, 3, RetryStrategy.EXPONENTIAL_JITTER, 50, 400, BREAKER, BUDGET, true))),
                new Slo(-1, 1500, 0, -1, -1, -1, 1500, -1), 8000);
    }

    static Experiment timeouts() {
        return new Experiment("timeouts", "Choosing a timeout",
                "How tight can the per-attempt timeout be before it hurts more than it helps?",
                "Payments normally answers in 20-60ms, but 25% of calls are slow by an extra 450ms. A short timeout "
                        + "abandons slow calls early and retries; a long one waits them out. Every variant retries up "
                        + "to 3 times with idempotency keys.",
                300, 60, 404, tuning("payments", new Tuning(40, 256, 512, 1000)),
                List.of(FaultPlan.latency("payments", "pay-slow", "/charge", 0.25, 450, BEFORE, 0, 0)),
                List.of(
                        v("t30", "Timeout 30ms", "Shorter than normal latency.",
                                policy(30, 3, RetryStrategy.EXPONENTIAL_JITTER, 20, 200, null, null, true)),
                        v("t150", "Timeout 150ms", "Above normal, below the slow tail.",
                                policy(150, 3, RetryStrategy.EXPONENTIAL_JITTER, 20, 200, null, null, true)),
                        v("t600", "Timeout 600ms", "Waits out the slow tail.",
                                policy(600, 3, RetryStrategy.EXPONENTIAL_JITTER, 20, 200, null, null, true)),
                        v("t3000", "Timeout 3000ms", "Effectively never gives up.",
                                policy(3000, 3, RetryStrategy.EXPONENTIAL_JITTER, 20, 200, null, null, true))),
                new Slo(0.99, 700, 0, 0, 0, -1, -1, -1), 8000);
    }

    static Experiment queueRedelivery() {
        List<FaultPlan> faults = List.of(
                FaultPlan.lostAck("worker", "ack-lost", 0.15),
                FaultPlan.latency("worker", "slow-worker", "process", 0.10, 1200, BEFORE, 0, 0),
                FaultPlan.duplicate("gateway", "dup-publish", "enqueue", 0.10));
        return new Experiment("queue-redelivery", "Queue settings and duplicate shipments",
                "Which queue configuration keeps a shipment from happening twice?",
                "Three things go wrong at once: 15% of acknowledgements are lost, 10% of jobs run slower (1.2s) "
                        + "than a short visibility timeout, and 10% of messages are published twice. At-least-once "
                        + "delivery means all three lead to redelivery. The inventory ledger counts real shipments.",
                300, 60, 505, tuning(), faults,
                List.of(
                        q("baseline", "Baseline", "800ms visibility, no dedup, consumer not idempotent.",
                                qc(800, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, false, false)),
                        q("dedup", "+ enqueue dedup", "Duplicate publishes are dropped by the queue.",
                                qc(800, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, true, false)),
                        q("idem", "+ idempotent consumer", "Duplicate deliveries are harmless; duplicate publishes still enqueue.",
                                qc(800, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, false, true)),
                        q("both", "+ dedup + idempotent consumer", "Both defences.",
                                qc(800, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, true, true)),
                        q("both-long-vis", "Both, 3s visibility", "Both defences, fewer spurious redeliveries.",
                                qc(3000, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, true, true))),
                // Drain target: 10s after the last order. The slow jobs alone (30 x 1.2s over 4 workers) need about 9s,
                // so a tighter target would fail every variant for capacity reasons and say nothing about queue settings.
                new Slo(-1, -1, -1, 0, 0, 0, -1, 10000), 20000);
    }

    static Experiment poisonJobs() {
        return new Experiment("poison-jobs", "Dead letters and job retries",
                "How many delivery attempts does a job need when its dependency is flaky?",
                "Inventory fails 35% of shipment commits (re-rolled on every delivery). The gateway has already told "
                        + "the customer 'accepted', so a job that exhausts its attempts is an order the customer was "
                        + "promised and never receives.",
                300, 60, 606, tuning(), List.of(FaultPlan.error("inventory", "commit-503", "/commit", 0.35, 503)),
                List.of(
                        q("a1", "1 attempt", "No redelivery.",
                                qc(2000, 1, RetryStrategy.NONE, 0, 0, 4, true, true)),
                        q("a3", "3 attempts, backoff", "Exponential + jitter 100-1000ms.",
                                qc(2000, 3, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, true, true)),
                        q("a6", "6 attempts, backoff", "Exponential + jitter 100-1000ms.",
                                qc(2000, 6, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, true, true)),
                        q("a6-nobackoff", "6 attempts, no backoff", "Redeliver immediately.",
                                qc(2000, 6, RetryStrategy.NONE, 0, 0, 4, true, true)),
                        q("a10", "10 attempts, backoff", "Exponential + jitter 100-1000ms.",
                                qc(2000, 10, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 4, true, true))),
                new Slo(-1, -1, -1, 0, 0, 0, -1, 8000), 15000);
    }

    static Experiment workerOutage() {
        return new Experiment("worker-outage", "Queue recovery after a worker outage",
                "After the whole worker tier is down for 3 seconds, how long until the backlog clears?",
                "Each shipment job takes about 150ms, and orders arrive at 10 per second. The worker tier is down from "
                        + "t+2s to t+5s while orders keep arriving. Work in flight at the moment of the crash is "
                        + "abandoned un-acknowledged, so it only comes back when its visibility timeout expires, and "
                        + "the backlog that built up during the outage has to be worked off by however many workers "
                        + "there are. Recovery means the last order shipped late (more than 1s after the customer was "
                        + "told 'accepted') has shipped. Compares visibility timeout and worker count.",
                120, 10, 707, tuning(),
                List.of(FaultPlan.latency("worker", "job-duration", "process", 1.0, 150, BEFORE, 0, 0),
                        FaultPlan.crash("worker", "worker-down", 2000, 5000)),
                List.of(
                        q("vis500-w2", "500ms visibility, 2 workers", "Fast redelivery, little parallelism.",
                                qc(500, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 2, true, true)),
                        q("vis5000-w2", "5s visibility, 2 workers", "Slow redelivery, little parallelism.",
                                qc(5000, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 2, true, true)),
                        q("vis500-w8", "500ms visibility, 8 workers", "Fast redelivery, more parallelism.",
                                qc(500, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 8, true, true)),
                        q("vis5000-w8", "5s visibility, 8 workers", "Slow redelivery, more parallelism.",
                                qc(5000, 5, RetryStrategy.EXPONENTIAL_JITTER, 100, 1000, 8, true, true))),
                new Slo(-1, -1, -1, 0, 0, 0, 4000, -1), 25000);
    }
}

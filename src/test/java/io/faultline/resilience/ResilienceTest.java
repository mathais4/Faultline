package io.faultline.resilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class ResilienceTest {

    private final AtomicLong now = new AtomicLong(1_000);

    private CircuitBreaker breaker() {
        return new CircuitBreaker(new CircuitBreaker.Config(10, 5, 0.5, 500, 2), now::get);
    }

    private static void call(CircuitBreaker b, boolean ok) {
        assertTrue(b.allow());
        b.record(ok);
    }

    @Test
    public void staysClosedBelowMinimumCalls() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 4; i++) call(b, false);
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
    }

    @Test
    public void opensAtThresholdAndRejects() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) call(b, false);
        assertEquals(CircuitBreaker.State.OPEN, b.state());
        assertFalse(b.allow());
        assertEquals(1, b.rejectedCalls());
    }

    @Test
    public void halfOpenThenClosesWhenProbesSucceed() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) call(b, false);
        now.addAndGet(500);
        call(b, true);
        assertEquals(CircuitBreaker.State.HALF_OPEN, b.state());
        call(b, true);
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
        assertTrue(b.allow());
    }

    @Test
    public void halfOpenFailureReopens() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) call(b, false);
        now.addAndGet(500);
        call(b, false);
        assertEquals(CircuitBreaker.State.OPEN, b.state());
        assertFalse(b.allow());
    }

    @Test
    public void halfOpenLimitsProbes() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) call(b, false);
        now.addAndGet(500);
        assertTrue(b.allow());
        assertTrue(b.allow());
        assertFalse(b.allow());
    }

    @Test
    public void retryBudgetCapsRetriesToRatio() {
        RetryBudget budget = new RetryBudget(0.2, 0, 10);
        int retries = 0;
        for (int i = 0; i < 100; i++) {
            budget.onRequest();
            if (budget.tryAcquireRetry()) retries++;
        }
        assertEquals(20, retries, 1);
    }

    @Test
    public void backoffShapes() {
        assertEquals(0, RetryStrategy.NONE.delayMs(1, 100, 1000, 0.5));
        assertEquals(100, RetryStrategy.FIXED.delayMs(3, 100, 1000, 0.5));
        assertEquals(100, RetryStrategy.EXPONENTIAL.delayMs(1, 100, 1000, 0.5));
        assertEquals(400, RetryStrategy.EXPONENTIAL.delayMs(3, 100, 1000, 0.5));
        assertEquals(1000, RetryStrategy.EXPONENTIAL.delayMs(9, 100, 1000, 0.5));
        assertEquals(200, RetryStrategy.EXPONENTIAL_JITTER.delayMs(2, 200, 1000, 0.5)); // 0.5 * 400
        assertEquals(0, RetryStrategy.EXPONENTIAL_JITTER.delayMs(2, 200, 1000, 0.0));
    }
}

package io.faultline.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded in-memory span buffer for one service. Collected over HTTP by the experiment runner. */
public final class SpanStore {
    private static final int CAP = 250_000;

    private final String service;
    private final ConcurrentLinkedQueue<Span> spans = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public SpanStore(String service) {
        this.service = service;
    }

    public String newSpanId() {
        return service + "-" + ids.incrementAndGet();
    }

    public void add(Span s) {
        if (size.incrementAndGet() > CAP) {
            size.decrementAndGet();
            dropped.incrementAndGet();
            return;
        }
        spans.add(s);
    }

    public List<Span> snapshot() {
        return new ArrayList<>(spans);
    }

    public long dropped() {
        return dropped.get();
    }

    public void clear() {
        spans.clear();
        size.set(0);
        dropped.set(0);
    }
}

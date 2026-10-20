package io.faultline.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Durable record of side effects, keyed by order. Survives simulated crashes (only the listener goes away) because
 * it plays the role of the service's database. The experiment runner reads it as ground truth.
 */
final class Ledger {
    private static final class Entry {
        final AtomicInteger count = new AtomicInteger();
        volatile long firstMs;
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** @return how many times this order's effect has now been applied (1 = first time). */
    int record(String orderId) {
        Entry e = entries.computeIfAbsent(orderId, k -> new Entry());
        int n = e.count.incrementAndGet();
        if (n == 1) e.firstMs = System.currentTimeMillis();
        return n;
    }

    Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", v.count.get());
            m.put("firstMs", v.firstMs);
            out.put(k, m);
        });
        return out;
    }

    void clear() {
        entries.clear();
    }
}

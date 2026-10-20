package io.faultline.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/** Per-service counters plus handler latency samples. Snapshot is served at /_faultline/metrics. */
public final class Metrics {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Double> latencies = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> peaks = new ConcurrentHashMap<>();

    public void inc(String name) {
        add(name, 1);
    }

    /** Track the maximum value ever observed for a gauge (e.g. in-flight requests). */
    public void peak(String name, long value) {
        peaks.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).accumulateAndGet(value, Math::max);
    }

    public void add(String name, long n) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(n);
    }

    public long get(String name) {
        LongAdder a = counters.get(name);
        return a == null ? 0 : a.sum();
    }

    public void recordLatency(double ms) {
        if (latencies.size() < 500_000) latencies.add(ms);
    }

    public void reset() {
        counters.clear();
        latencies.clear();
        peaks.clear();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        Map<String, Long> c = new TreeMap<>();
        counters.forEach((k, v) -> c.put(k, v.sum()));
        out.put("counters", c);
        Map<String, Long> p = new TreeMap<>();
        peaks.forEach((k, v) -> p.put(k, v.get()));
        out.put("peaks", p);
        List<Double> lat = new ArrayList<>(latencies);
        out.put("latency", Stats.summarize(lat));
        return out;
    }
}

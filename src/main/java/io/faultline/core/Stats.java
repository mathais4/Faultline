package io.faultline.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact (not bucketed) latency statistics. Trials are small enough to keep every sample. */
public final class Stats {
    private Stats() {}

    public record Summary(int count, double mean, double p50, double p90, double p95, double p99, double max)
            implements Json.Jsonable {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0);

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", count);
            m.put("mean", round(mean));
            m.put("p50", round(p50));
            m.put("p90", round(p90));
            m.put("p95", round(p95));
            m.put("p99", round(p99));
            m.put("max", round(max));
            return m;
        }
    }

    public static Summary summarize(List<Double> samples) {
        double[] a = new double[samples.size()];
        for (int i = 0; i < a.length; i++) a[i] = samples.get(i);
        return summarize(a);
    }

    public static Summary summarize(double[] samples) {
        if (samples.length == 0) return Summary.EMPTY;
        double[] s = samples.clone();
        Arrays.sort(s);
        double sum = 0;
        for (double v : s) sum += v;
        return new Summary(s.length, sum / s.length, percentile(s, 50), percentile(s, 90), percentile(s, 95),
                percentile(s, 99), s[s.length - 1]);
    }

    /** Nearest-rank percentile over an ascending-sorted array. p in (0, 100]. */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        int idx = Math.min(sorted.length - 1, Math.max(0, rank - 1));
        return sorted[idx];
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

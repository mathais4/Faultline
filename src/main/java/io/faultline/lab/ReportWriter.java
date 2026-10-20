package io.faultline.lab;

import io.faultline.core.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the results of a run (the parsed results.json) into a self-contained HTML report and a Markdown summary.
 * Working from the JSON means a report can be regenerated later without re-running anything.
 */
public final class ReportWriter {

    private static final String[] COLORS = {"#2a6fdb", "#d9730d", "#1f9d6b", "#c2417a", "#7a5af8", "#9a7b00"};

    /** A column of the results table: metric key, heading, formatter, and whether SLO makes it always relevant. */
    private record Col(String key, String head, String unit, int decimals, boolean pct, String sloField) {}

    private static final List<Col> COLS = List.of(
            new Col("successRate", "Success", "", 1, true, "minSuccessRate"),
            new Col("p50", "p50", "ms", 0, false, null),
            new Col("p95", "p95", "ms", 0, false, null),
            new Col("p99", "p99", "ms", 0, false, "maxP99Ms"),
            new Col("doubleCharged", "Charged twice", "", 0, false, "maxDoubleCharged"),
            new Col("chargedNotAccepted", "Charged, order failed", "", 0, false, null),
            new Col("doubleShipped", "Shipped twice", "", 0, false, "maxDoubleShipped"),
            new Col("lostOrders", "Accepted, never shipped", "", 0, false, "maxLostOrders"),
            new Col("deadJobs", "Dead-lettered", "", 0, false, "maxDeadJobs"),
            new Col("paymentsAmplification", "Payments attempts per call", "x", 2, false, null),
            new Col("windowSuccessRate", "Success during outage", "", 1, true, null),
            new Col("recoveryMs", "Recovery after outage", "ms", 0, false, "maxRecoveryMs"),
            new Col("drainMs", "Queue drain", "ms", 0, false, "maxDrainMs"),
            new Col("shipLagP99", "Accepted-to-shipped p99", "ms", 0, false, null),
            new Col("redeliveries", "Redeliveries", "", 0, false, null));

    private ReportWriter() {}

    // ================================================================================================ HTML

    @SuppressWarnings("unchecked")
    public static String html(Map<String, Object> run) {
        StringBuilder h = new StringBuilder(64_000);
        List<Object> exps = Json.list(run, "experiments");
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Faultline Report</title><style>").append(CSS).append("</style></head><body><main>");

        h.append("<header><h1>Faultline</h1><p class=\"sub\">Which resilience strategies actually worked</p>");
        h.append("<p class=\"meta\">Run ").append(esc(Json.str(run, "generatedAt", ""))).append(" &middot; queue: <b>")
                .append(esc(Json.str(run, "queueBackend", "?"))).append("</b> &middot; ")
                .append(Json.integer(run, "trials", 1)).append(" trial(s) per variant &middot; Java ")
                .append(esc(Json.str(run, "java", "?"))).append(" on ").append(Json.integer(run, "cpus", 0))
                .append(" CPUs</p></header>");

        h.append("<section class=\"card\"><h2>Scorecard</h2><p class=\"note\">A variant <b>worked</b> only if every "
                + "trial met every check in its experiment's SLO, so a strategy that is fast but charges customers "
                + "twice does not pass.</p><table class=\"score\"><thead><tr><th>Experiment</th>"
                + "<th>Worked</th><th>Partial</th><th>Failed</th></tr></thead><tbody>");
        for (Object eo : exps) {
            Map<String, Object> e = (Map<String, Object>) eo;
            StringBuilder w = new StringBuilder(), p = new StringBuilder(), f = new StringBuilder();
            for (Object vo : Json.list(e, "variants")) {
                Map<String, Object> v = (Map<String, Object>) vo;
                String chip = "<span class=\"chip\">" + esc(Json.str(v, "label", "")) + "</span>";
                switch (Json.str(v, "verdict", "")) {
                    case "worked" -> w.append(chip);
                    case "partial" -> p.append(chip);
                    case "failed" -> f.append(chip);
                    default -> w.append(chip);
                }
            }
            h.append("<tr><td><a href=\"#").append(esc(Json.str(e, "id", ""))).append("\">")
                    .append(esc(Json.str(e, "title", ""))).append("</a></td><td class=\"g\">").append(w)
                    .append("</td><td class=\"a\">").append(p).append("</td><td class=\"r\">").append(f)
                    .append("</td></tr>");
        }
        h.append("</tbody></table></section>");

        for (Object eo : exps) h.append(experiment((Map<String, Object>) eo));

        h.append("<section class=\"card\"><h2>How to read and trust this</h2><ul>")
                .append("<li><b>Same faults for every variant.</b> Fault decisions are a pure function of seed, "
                        + "fault id, order id and attempt number, never of thread timing, so each variant is hit by "
                        + "the same planned faults. The <em>planned fault fingerprint</em> on each experiment is a "
                        + "hash of that schedule; re-running with the same seed reproduces it.</li>")
                .append("<li><b>Timing is not deterministic.</b> Latencies, retry interleavings and queue "
                        + "pickup times vary run to run, which is why each variant runs several trials and the table "
                        + "shows mean with the min&ndash;max range.</li>")
                .append("<li><b>Ground truth, not client opinion.</b> Double charges, double shipments and lost orders "
                        + "come from the payments and inventory ledgers and the queue table.</li>")
                .append("<li><b>Scale.</b> All services run on one machine, so absolute latencies mean little; the "
                        + "differences between variants are the finding. Fault injection happens inside each "
                        + "service (it simulates the failure), not by breaking real networks.</li>")
                .append("<li><b>Queue backend:</b> ").append(esc(Json.str(run, "queueBackend", "?")))
                .append(". An in-memory queue models PostgreSQL semantics but does not measure them.</li></ul></section>");
        h.append("</main></body></html>");
        return h.toString();
    }

    @SuppressWarnings("unchecked")
    private static String experiment(Map<String, Object> e) {
        StringBuilder h = new StringBuilder();
        List<Object> variants = Json.list(e, "variants");
        Map<String, Object> slo = Json.map(e, "slo");
        h.append("<section class=\"card\" id=\"").append(esc(Json.str(e, "id", ""))).append("\"><h2>")
                .append(esc(Json.str(e, "title", ""))).append("</h2><p class=\"q\">")
                .append(esc(Json.str(e, "question", ""))).append("</p><p>")
                .append(esc(Json.str(e, "description", ""))).append("</p>");

        h.append("<h3>Injected faults</h3><ul class=\"faults\">");
        for (Object fo : Json.list(e, "faults")) h.append("<li>").append(esc(Json.str((Map<String, Object>) fo, "text", ""))).append("</li>");
        h.append("</ul><p class=\"note\">").append(Json.integer(e, "orders", 0)).append(" orders at ")
                .append(Json.integer(e, "ratePerSec", 0)).append("/s &middot; seed ").append(Json.lng(e, "seed", 0))
                .append(" &middot; planned fault fingerprint <code>").append(esc(Json.str(e, "plannedFaultFingerprint", "")))
                .append("</code> &middot; SLO: ").append(esc(sloText(slo))).append("</p>");

        // ---- table
        h.append("<h3>Results</h3><div class=\"scroll\"><table class=\"res\"><thead><tr><th>Variant</th><th>Verdict</th>");
        List<Col> cols = new ArrayList<>();
        for (Col c : COLS) if (relevant(c, variants, slo)) cols.add(c);
        for (Col c : cols) h.append("<th>").append(esc(c.head)).append("</th>");
        h.append("</tr></thead><tbody>");
        java.util.Set<String> policies = new java.util.HashSet<>();
        for (Object vo : variants) policies.add(Json.str((Map<String, Object>) vo, "policyText", ""));
        boolean policyVaries = policies.size() > 1;
        int idx = 0;
        for (Object vo : variants) {
            Map<String, Object> v = (Map<String, Object>) vo;
            String verdict = Json.str(v, "verdict", "n/a");
            h.append("<tr><td><span class=\"dot\" style=\"background:").append(COLORS[idx % COLORS.length])
                    .append("\"></span><b>").append(esc(Json.str(v, "label", ""))).append("</b><br><span class=\"small\">")
                    .append(esc(describe(v, policyVaries))).append("</span></td><td><span class=\"v ").append(verdict).append("\">")
                    .append(verdict.toUpperCase(Locale.ROOT)).append("</span>");
            List<Object> fails = Json.list(v, "failedChecks");
            if (!fails.isEmpty()) {
                h.append("<ul class=\"small fails\">");
                for (Object f : fails) h.append("<li>").append(esc(String.valueOf(f))).append("</li>");
                h.append("</ul>");
            }
            h.append("</td>");
            Map<String, Object> sum = Json.map(v, "summary");
            for (Col c : cols) h.append(cell(Json.map(sum, c.key), c, Json.integer(e, "trials", 1)));
            h.append("</tr>");
            idx++;
        }
        h.append("</tbody></table></div>");
        if (Json.integer(e, "trials", 1) > 1) {
            h.append("<p class=\"note\">Cells show the mean over ").append(Json.integer(e, "trials", 1))
                    .append(" trials with the min&ndash;max range underneath.</p>");
        }

        // ---- charts
        h.append("<div class=\"charts\"><div><h3>Order success over time</h3>").append(timeline(e)).append("</div>")
                .append("<div><h3>Latency percentiles (all orders)</h3>").append(percentiles(e)).append("</div></div>");
        if (Json.dbl(slo, "maxRecoveryMs", -1) >= 0 || Json.dbl(slo, "maxDrainMs", -1) >= 0) {
            h.append("<div class=\"charts\"><div><h3>Time to ship, by when the order arrived</h3>").append(lagTimeline(e))
                    .append("<p class=\"note\">Mean delay between the customer being told &ldquo;accepted&rdquo; and the "
                            + "shipment happening. A backlog or a stranded job shows up as a delay that outlives the fault window.</p></div></div>");
        }

        // ---- findings
        List<String> findings = findings(e);
        if (!findings.isEmpty()) {
            h.append("<h3>What the data says</h3><ul class=\"findings\">");
            for (String f : findings) h.append("<li>").append(f).append("</li>");
            h.append("</ul>");
        }

        // ---- traces
        h.append("<h3>Sample request traces</h3><p class=\"note\">Taken from trial 1 of each variant. Bars show when "
                + "each hop started and how long it took; red hops failed, amber hops were short-circuited.</p>");
        for (Object vo : variants) {
            Map<String, Object> v = (Map<String, Object>) vo;
            List<Object> traces = Json.list(v, "sampleTraces");
            if (traces.isEmpty()) continue;
            h.append("<details><summary>").append(esc(Json.str(v, "label", ""))).append(" &middot; ")
                    .append(traces.size()).append(" trace(s)</summary>");
            for (Object to : traces) h.append(trace((Map<String, Object>) to));
            h.append("</details>");
        }
        h.append("</section>");
        return h.toString();
    }

    /** Synchronous-policy experiments vary the policy; queue experiments vary the queue configuration. */
    private static String describe(Map<String, Object> v, boolean policyVaries) {
        String detail = policyVaries ? Json.str(v, "policyText", "") : Json.str(v, "queueText", "");
        String desc = Json.str(v, "description", "");
        return desc.isEmpty() ? detail : desc + " [" + detail + "]";
    }

    private static boolean relevant(Col c, List<Object> variants, Map<String, Object> slo) {
        if (c.key.equals("successRate") || c.key.equals("p50") || c.key.equals("p95") || c.key.equals("p99")) return true;
        if (c.sloField != null && Json.dbl(slo, c.sloField, -1) >= 0) return true;
        if (c.key.equals("drainMs")) return false; // only meaningful when the experiment judges it
        if (c.key.equals("shipLagP99")) return Json.dbl(slo, "maxRecoveryMs", -1) >= 0 || Json.dbl(slo, "maxDrainMs", -1) >= 0;
        for (Object vo : variants) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = Json.map(Json.map((Map<String, Object>) vo, "summary"), c.key);
            if (m == null || m.get("max") == null) continue;
            double max = Json.dbl(m, "max", 0), min = Json.dbl(m, "min", 0);
            if (c.key.equals("paymentsAmplification")) {
                if (max > 1.01) return true;
            } else if (max != 0 || min != 0) {
                return true;
            }
        }
        return false;
    }

    private static String cell(Map<String, Object> agg, Col c, int trials) {
        if (agg == null || agg.get("mean") == null) return "<td class=\"num\">&ndash;</td>";
        double mean = Json.dbl(agg, "mean", 0), min = Json.dbl(agg, "min", 0), max = Json.dbl(agg, "max", 0);
        String s = "<td class=\"num\"><b>" + num(mean, c) + "</b>";
        if (trials > 1 && Math.abs(max - min) > 1e-9) s += "<br><span class=\"small\">" + num(min, c) + "&ndash;" + num(max, c) + "</span>";
        return s + "</td>";
    }

    private static String num(double x, Col c) {
        if (c.pct) return String.format(Locale.ROOT, "%." + c.decimals + "f%%", x * 100);
        String f = c.decimals == 0 ? String.format(Locale.ROOT, "%,.0f", x) : String.format(Locale.ROOT, "%." + c.decimals + "f", x);
        return f + (c.unit.isEmpty() ? "" : (c.unit.equals("x") ? "x" : "&nbsp;" + c.unit));
    }

    private static String sloText(Map<String, Object> s) {
        List<String> p = new ArrayList<>();
        if (Json.dbl(s, "minSuccessRate", -1) >= 0) p.add("success &ge; " + Math.round(Json.dbl(s, "minSuccessRate", 0) * 1000) / 10.0 + "%");
        if (Json.dbl(s, "maxP99Ms", -1) >= 0) p.add("p99 &le; " + Math.round(Json.dbl(s, "maxP99Ms", 0)) + "ms");
        if (Json.dbl(s, "maxDoubleCharged", -1) >= 0) p.add("charged twice &le; " + Json.integer(s, "maxDoubleCharged", 0));
        if (Json.dbl(s, "maxDoubleShipped", -1) >= 0) p.add("shipped twice &le; " + Json.integer(s, "maxDoubleShipped", 0));
        if (Json.dbl(s, "maxLostOrders", -1) >= 0) p.add("lost orders &le; " + Json.integer(s, "maxLostOrders", 0));
        if (Json.dbl(s, "maxDeadJobs", -1) >= 0) p.add("dead letters &le; " + Json.integer(s, "maxDeadJobs", 0));
        if (Json.dbl(s, "maxRecoveryMs", -1) >= 0) p.add("recovery &le; " + Math.round(Json.dbl(s, "maxRecoveryMs", 0)) + "ms");
        if (Json.dbl(s, "maxDrainMs", -1) >= 0) p.add("queue drains in &le; " + Math.round(Json.dbl(s, "maxDrainMs", 0)) + "ms");
        return p.isEmpty() ? "none (informational)" : String.join(", ", p).replace("&ge;", "≥").replace("&le;", "≤");
    }

    // ------------------------------------------------------------------------------------------------ charts

    @SuppressWarnings("unchecked")
    private static String timeline(Map<String, Object> e) {
        List<Object> variants = Json.list(e, "variants");
        double dur = Json.dbl(e, "loadDurationMs", 1000);
        int w = 560, hgt = 210, l = 38, r = 10, t = 10, b = 28;
        double pw = w - l - r, ph = hgt - t - b;
        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(hgt).append("\" class=\"chart\" role=\"img\" "
                + "aria-label=\"Order success rate over time for each variant\">");
        Map<String, Object> win = Json.map(e, "faultWindow");
        if (win != null) {
            double x0 = l + pw * Json.dbl(win, "startMs", 0) / dur, x1 = l + pw * Json.dbl(win, "endMs", 0) / dur;
            s.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%.1f\" class=\"win\"/>"
                    + "<text x=\"%.1f\" y=\"%d\" class=\"lab\" text-anchor=\"middle\">fault window</text>", x0, t, x1 - x0, ph, (x0 + x1) / 2, t + 12));
        }
        for (int pct = 0; pct <= 100; pct += 25) {
            double y = t + ph * (1 - pct / 100.0);
            s.append(String.format(Locale.ROOT, "<line x1=\"%d\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\" class=\"grid\"/>"
                    + "<text x=\"%d\" y=\"%.1f\" class=\"lab\" text-anchor=\"end\">%d%%</text>", l, l + pw, y, y, l - 5, y + 3, pct));
        }
        int ticks = 5;
        for (int i = 0; i <= ticks; i++) {
            double x = l + pw * i / ticks;
            s.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" class=\"lab\" text-anchor=\"middle\">%.1fs</text>", x, hgt - 10, dur * i / ticks / 1000.0));
        }
        int idx = 0;
        for (Object vo : variants) {
            Map<String, Object> v = (Map<String, Object>) vo;
            List<Object> trials = Json.list(v, "trials");
            Map<Long, long[]> agg = new java.util.TreeMap<>();
            for (Object to : trials) {
                for (Object po : Json.list((Map<String, Object>) to, "timeline")) {
                    Map<String, Object> pt = (Map<String, Object>) po;
                    long[] a = agg.computeIfAbsent(Json.lng(pt, "t", 0), k -> new long[2]);
                    a[0] += Json.lng(pt, "n", 0);
                    a[1] += Json.lng(pt, "ok", 0);
                }
            }
            StringBuilder path = new StringBuilder();
            boolean pen = false;
            for (Map.Entry<Long, long[]> en : agg.entrySet()) {
                long[] a = en.getValue();
                if (a[0] == 0) {
                    pen = false;
                    continue;
                }
                double x = l + pw * (en.getKey() + 125) / dur, y = t + ph * (1 - a[1] / (double) a[0]);
                path.append(pen ? "L" : "M").append(String.format(Locale.ROOT, "%.1f %.1f ", x, y));
                pen = true;
            }
            s.append("<path d=\"").append(path).append("\" fill=\"none\" stroke=\"").append(COLORS[idx % COLORS.length])
                    .append("\" stroke-width=\"2\" stroke-linejoin=\"round\"/>");
            idx++;
        }
        s.append("</svg>").append(legend(variants));
        return s.toString();
    }

    /** Mean accepted-to-shipped delay per arrival-time bucket, one line per variant, on a seconds axis. */
    @SuppressWarnings("unchecked")
    private static String lagTimeline(Map<String, Object> e) {
        List<Object> variants = Json.list(e, "variants");
        double dur = Json.dbl(e, "loadDurationMs", 1000);
        List<Map<Long, double[]>> perVariant = new ArrayList<>();
        double maxLag = 1000;
        for (Object vo : variants) {
            Map<Long, double[]> agg = new java.util.TreeMap<>();
            for (Object to : Json.list((Map<String, Object>) vo, "trials")) {
                for (Object po : Json.list((Map<String, Object>) to, "timeline")) {
                    Map<String, Object> pt = (Map<String, Object>) po;
                    double[] a = agg.computeIfAbsent(Json.lng(pt, "t", 0), k -> new double[2]);
                    a[0] += Json.dbl(pt, "lagSum", 0);
                    a[1] += Json.dbl(pt, "lagN", 0);
                }
            }
            for (double[] a : agg.values()) if (a[1] > 0) maxLag = Math.max(maxLag, a[0] / a[1]);
            perVariant.add(agg);
        }
        double top = Math.ceil(maxLag / 1000.0) * 1000.0; // axis in whole seconds
        int steps = (int) Math.min(5, top / 1000.0);
        int w = 560, hgt = 210, l = 38, r = 10, t = 10, b = 28;
        double pw = w - l - r, ph = hgt - t - b;
        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(hgt).append("\" class=\"chart\" role=\"img\" "
                + "aria-label=\"Mean time from accepted to shipped, by order arrival time, for each variant\">");
        Map<String, Object> win = Json.map(e, "faultWindow");
        if (win != null) {
            double x0 = l + pw * Json.dbl(win, "startMs", 0) / dur, x1 = l + pw * Json.dbl(win, "endMs", 0) / dur;
            s.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%.1f\" class=\"win\"/>"
                    + "<text x=\"%.1f\" y=\"%d\" class=\"lab\" text-anchor=\"middle\">fault window</text>", x0, t, x1 - x0, ph, (x0 + x1) / 2, t + 12));
        }
        for (int i = 0; i <= steps; i++) {
            double v = top * i / steps, y = t + ph * (1 - v / top);
            s.append(String.format(Locale.ROOT, "<line x1=\"%d\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\" class=\"grid\"/>"
                    + "<text x=\"%d\" y=\"%.1f\" class=\"lab\" text-anchor=\"end\">%.0fs</text>", l, l + pw, y, y, l - 5, y + 3, v / 1000.0));
        }
        for (int i = 0; i <= 5; i++) {
            double x = l + pw * i / 5;
            s.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" class=\"lab\" text-anchor=\"middle\">%.1fs</text>", x, hgt - 10, dur * i / 5 / 1000.0));
        }
        int idx = 0;
        for (Map<Long, double[]> agg : perVariant) {
            StringBuilder path = new StringBuilder();
            boolean pen = false;
            for (Map.Entry<Long, double[]> en : agg.entrySet()) {
                double[] a = en.getValue();
                if (a[1] == 0) {
                    pen = false;
                    continue;
                }
                double x = l + pw * (en.getKey() + 125) / dur, y = t + ph * (1 - Math.min(1.0, a[0] / a[1] / top));
                path.append(pen ? "L" : "M").append(String.format(Locale.ROOT, "%.1f %.1f ", x, y));
                pen = true;
            }
            s.append("<path d=\"").append(path).append("\" fill=\"none\" stroke=\"").append(COLORS[idx % COLORS.length])
                    .append("\" stroke-width=\"2\" stroke-linejoin=\"round\"/>");
            idx++;
        }
        s.append("</svg>").append(legend(variants));
        return s.toString();
    }

    @SuppressWarnings("unchecked")
    private static String percentiles(Map<String, Object> e) {
        List<Object> variants = Json.list(e, "variants");
        double max = 1;
        for (Object vo : variants) max = Math.max(max, Json.dbl(Json.map(Json.map((Map<String, Object>) vo, "summary"), "p99"), "mean", 0));
        int rowH = 34, w = 560, l = 8, barW = w - l - 12;
        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox=\"0 0 ").append(w).append(' ').append(variants.size() * rowH + 22)
                .append("\" class=\"chart\" role=\"img\" aria-label=\"p50, p95 and p99 latency per variant\">");
        int i = 0;
        for (Object vo : variants) {
            Map<String, Object> v = (Map<String, Object>) vo;
            Map<String, Object> sum = Json.map(v, "summary");
            double p50 = Json.dbl(Json.map(sum, "p50"), "mean", 0), p95 = Json.dbl(Json.map(sum, "p95"), "mean", 0),
                    p99 = Json.dbl(Json.map(sum, "p99"), "mean", 0);
            int y = 4 + i * rowH;
            String c = COLORS[i % COLORS.length];
            s.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" class=\"lab\">%s</text>", l, y + 9, esc(Json.str(v, "label", ""))));
            s.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"12\" fill=\"%s\" opacity=\".25\" rx=\"2\"/>", l, y + 14, barW * p99 / max, c));
            s.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"12\" fill=\"%s\" opacity=\".5\" rx=\"2\"/>", l, y + 14, barW * p95 / max, c));
            s.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"12\" fill=\"%s\" rx=\"2\"/>", l, y + 14, Math.max(1, barW * p50 / max), c));
            s.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" class=\"lab\" text-anchor=\"end\">p50 %,.0f &#183; p95 %,.0f &#183; p99 %,.0f ms</text>", w - 6, y + 9, p50, p95, p99));
            i++;
        }
        s.append("</svg><p class=\"note\">Dark bar = p50, medium = p95, light = p99. Means over trials; failed orders are included, since a slow failure is still latency the customer feels.</p>");
        return s.toString();
    }

    @SuppressWarnings("unchecked")
    private static String legend(List<Object> variants) {
        StringBuilder s = new StringBuilder("<p class=\"legend\">");
        int i = 0;
        for (Object vo : variants) {
            s.append("<span><i style=\"background:").append(COLORS[i++ % COLORS.length]).append("\"></i>")
                    .append(esc(Json.str((Map<String, Object>) vo, "label", ""))).append("</span>");
        }
        return s.append("</p>").toString();
    }

    // ------------------------------------------------------------------------------------------------ traces

    @SuppressWarnings("unchecked")
    private static String trace(Map<String, Object> t) {
        List<Object> spans = Json.list(t, "spans");
        if (spans.isEmpty()) return "";
        long start = Long.MAX_VALUE;
        double end = 0;
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Object so : spans) {
            Map<String, Object> s = (Map<String, Object>) so;
            start = Math.min(start, Json.lng(s, "startMs", 0));
            end = Math.max(end, Json.lng(s, "startMs", 0) + Json.dbl(s, "durationMs", 0));
            byId.put(Json.str(s, "spanId", ""), s);
        }
        double total = Math.max(1, end - start);
        StringBuilder h = new StringBuilder();
        h.append("<div class=\"trace\"><div class=\"th\"><b>").append(esc(Json.str(t, "kind", ""))).append("</b> &middot; order ")
                .append(esc(Json.str(t, "orderId", ""))).append(" &middot; ").append(String.format(Locale.ROOT, "%,.0f", total)).append("ms</div>");
        for (Object so : spans) {
            Map<String, Object> s = (Map<String, Object>) so;
            int depth = 0;
            Map<String, Object> cur = s;
            while (depth < 6 && byId.containsKey(Json.str(cur, "parentId", "?"))) {
                cur = byId.get(Json.str(cur, "parentId", ""));
                depth++;
            }
            double off = (Json.lng(s, "startMs", 0) - start) / total * 100, wd = Math.max(0.6, Json.dbl(s, "durationMs", 0) / total * 100);
            if (off + wd > 100) wd = Math.max(0.6, 100 - off);
            String status = Json.str(s, "status", "");
            String cls = status.equals("ok") ? "ok" : (status.equals("breaker_open") || status.equals("rejected")) ? "warn" : "bad";
            Map<String, Object> tags = Json.map(s, "tags");
            StringBuilder tip = new StringBuilder();
            if (tags != null) tags.forEach((k, v) -> tip.append(k).append('=').append(v).append("  "));
            h.append("<div class=\"row\" title=\"").append(esc(tip.toString().trim())).append("\"><div class=\"lbl\" style=\"padding-left:")
                    .append(depth * 12).append("px\">").append(esc(Json.str(s, "service", ""))).append(" &middot; ")
                    .append(esc(Json.str(s, "name", ""))).append(attemptSuffix(tags)).append("</div><div class=\"bar\"><i class=\"")
                    .append(cls).append("\" style=\"left:").append(String.format(Locale.ROOT, "%.2f", off)).append("%;width:")
                    .append(String.format(Locale.ROOT, "%.2f", wd)).append("%\"></i></div><div class=\"st ").append(cls).append("\">")
                    .append(esc(status)).append(" &middot; ").append(String.format(Locale.ROOT, "%,.0f", Json.dbl(s, "durationMs", 0)))
                    .append("ms</div></div>");
        }
        return h.append("</div>").toString();
    }

    private static String attemptSuffix(Map<String, Object> tags) {
        if (tags == null || !tags.containsKey("attempt")) return "";
        String a = String.valueOf(tags.get("attempt"));
        return a.equals("1") ? "" : " (attempt " + esc(a) + ")";
    }

    // ------------------------------------------------------------------------------------------------ findings

    /** Plain-language observations computed from the numbers. HTML-safe strings. */
    @SuppressWarnings("unchecked")
    static List<String> findings(Map<String, Object> e) {
        List<String> out = new ArrayList<>();
        List<Object> vs = Json.list(e, "variants");
        if (vs.size() < 2) return out;
        Map<String, Object> base = (Map<String, Object>) vs.get(0);
        String baseLabel = esc(Json.str(base, "label", ""));

        List<String> worked = new ArrayList<>(), failed = new ArrayList<>(), partial = new ArrayList<>();
        for (Object vo : vs) {
            Map<String, Object> v = (Map<String, Object>) vo;
            String verdict = Json.str(v, "verdict", "");
            if (verdict.equals("worked")) worked.add(esc(Json.str(v, "label", "")));
            if (verdict.equals("failed")) failed.add(esc(Json.str(v, "label", "")));
            if (verdict.equals("partial")) {
                partial.add(esc(Json.str(v, "label", "")) + " (" + Json.integer(v, "trialsPassed", 0) + " of "
                        + Json.list(v, "trials").size() + " trials)");
            }
        }
        boolean hasSlo = vs.stream().anyMatch(vo -> !"n/a".equals(Json.str((Map<String, Object>) vo, "verdict", "")));
        if (hasSlo) {
            if (worked.isEmpty()) out.add("<b>No variant met the SLO</b> in every trial.");
            else out.add("<b>Met the SLO in every trial:</b> " + String.join("; ", worked) + ".");
        }

        for (int i = 1; i < vs.size(); i++) {
            Map<String, Object> v = (Map<String, Object>) vs.get(i);
            String label = esc(Json.str(v, "label", ""));
            List<String> parts = new ArrayList<>();
            double s0 = mean(base, "successRate"), s1 = mean(v, "successRate");
            if (Math.abs(s1 - s0) >= 0.02) parts.add(String.format(Locale.ROOT, "success %s from %.1f%% to %.1f%%", s1 > s0 ? "rose" : "fell", s0 * 100, s1 * 100));
            double c0 = mean(base, "doubleCharged"), c1 = mean(v, "doubleCharged");
            if (c1 - c0 >= 1) parts.add(String.format(Locale.ROOT, "customers charged twice went from %.0f to %.0f", c0, c1));
            if (c0 - c1 >= 1) parts.add(String.format(Locale.ROOT, "customers charged twice fell from %.0f to %.0f", c0, c1));
            double n0 = mean(base, "chargedNotAccepted"), n1 = mean(v, "chargedNotAccepted");
            if (Math.abs(n1 - n0) >= 5) parts.add(String.format(Locale.ROOT, "customers charged but shown a failure went from %.0f to %.0f", n0, n1));
            double d0 = mean(base, "doubleShipped"), d1 = mean(v, "doubleShipped");
            if (Math.abs(d1 - d0) >= 1) parts.add(String.format(Locale.ROOT, "orders shipped twice went from %.0f to %.0f", d0, d1));
            double l0 = mean(base, "lostOrders"), l1 = mean(v, "lostOrders");
            if (Math.abs(l1 - l0) >= 1) parts.add(String.format(Locale.ROOT, "accepted-but-never-shipped orders went from %.0f to %.0f", l0, l1));
            double p0 = mean(base, "p99"), p1 = mean(v, "p99");
            if (p0 > 0 && (p1 / p0 >= 1.5 || p1 / p0 <= 0.67) && Math.abs(p1 - p0) >= 50) parts.add(String.format(Locale.ROOT, "p99 latency went from %,.0fms to %,.0fms", p0, p1));
            double a1 = mean(v, "paymentsAmplification");
            if (a1 >= 1.3) parts.add(String.format(Locale.ROOT, "sent %.2f payment attempts per call", a1));
            double r0 = mean(base, "recoveryMs"), r1 = mean(v, "recoveryMs");
            if (!Double.isNaN(r0) && !Double.isNaN(r1) && Math.abs(r1 - r0) >= 250) parts.add(String.format(Locale.ROOT, "recovery after the outage went from %,.0fms to %,.0fms", r0, r1));
            double q0 = mean(base, "drainMs"), q1 = mean(v, "drainMs");
            if (Math.abs(q1 - q0) >= 1000) parts.add(String.format(Locale.ROOT, "queue drain time went from %,.0fms to %,.0fms", q0, q1));
            double k0 = mean(base, "deadJobs"), k1 = mean(v, "deadJobs");
            if (Math.abs(k1 - k0) >= 2) parts.add(String.format(Locale.ROOT, "dead-lettered jobs went from %.0f to %.0f", k0, k1));
            if (!parts.isEmpty()) out.add("Compared with <i>" + baseLabel + "</i>, <b>" + label + "</b>: " + String.join("; ", parts) + ".");
        }
        if (!partial.isEmpty() && hasSlo) out.add("Met the SLO in some trials but not others (not reliable): " + String.join("; ", partial) + ".");
        if (!failed.isEmpty() && hasSlo) out.add("Failed every trial: " + String.join("; ", failed) + ".");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static double mean(Map<String, Object> variant, String metric) {
        Map<String, Object> m = Json.map(Json.map(variant, "summary"), metric);
        if (m == null || m.get("mean") == null) return Double.NaN;
        return Json.dbl(m, "mean", Double.NaN);
    }

    // ================================================================================================ Markdown

    @SuppressWarnings("unchecked")
    public static String markdown(Map<String, Object> run) {
        StringBuilder s = new StringBuilder();
        s.append("# Faultline report\n\n");
        s.append("Run ").append(Json.str(run, "generatedAt", "")).append(" | queue: ").append(Json.str(run, "queueBackend", "?"))
                .append(" | ").append(Json.integer(run, "trials", 1)).append(" trial(s) per variant | Java ")
                .append(Json.str(run, "java", "?")).append("\n\n");
        s.append("A variant **worked** only if every trial met every check in the experiment's SLO.\n\n");
        for (Object eo : Json.list(run, "experiments")) {
            Map<String, Object> e = (Map<String, Object>) eo;
            List<Object> variants = Json.list(e, "variants");
            s.append("## ").append(Json.str(e, "title", "")).append("\n\n**").append(Json.str(e, "question", "")).append("**\n\n")
                    .append(Json.str(e, "description", "")).append("\n\n");
            s.append("Faults:\n");
            for (Object fo : Json.list(e, "faults")) s.append("- ").append(Json.str((Map<String, Object>) fo, "text", "")).append('\n');
            s.append("\nSLO: ").append(sloText(Json.map(e, "slo"))).append(" | planned fault fingerprint `")
                    .append(Json.str(e, "plannedFaultFingerprint", "")).append("`\n\n");
            List<Col> cols = new ArrayList<>();
            for (Col c : COLS) if (relevant(c, variants, Json.map(e, "slo"))) cols.add(c);
            s.append("| Variant | Verdict |");
            for (Col c : cols) s.append(' ').append(c.head).append(" |");
            s.append("\n|---|---|");
            for (int i = 0; i < cols.size(); i++) s.append("---:|");
            s.append('\n');
            for (Object vo : variants) {
                Map<String, Object> v = (Map<String, Object>) vo;
                s.append("| ").append(Json.str(v, "label", "")).append(" | ").append(Json.str(v, "verdict", "").toUpperCase(Locale.ROOT)).append(" |");
                for (Col c : cols) {
                    Map<String, Object> agg = Json.map(Json.map(v, "summary"), c.key);
                    s.append(' ').append(agg == null || agg.get("mean") == null ? "-" : num(Json.dbl(agg, "mean", 0), c).replace("&nbsp;", " ")).append(" |");
                }
                s.append('\n');
            }
            s.append('\n');
            for (Object vo : variants) {
                Map<String, Object> v = (Map<String, Object>) vo;
                List<Object> fails = Json.list(v, "failedChecks");
                if (!fails.isEmpty()) {
                    s.append("- **").append(Json.str(v, "label", "")).append("** missed: ");
                    List<String> fl = new ArrayList<>();
                    for (Object f : fails) fl.add(String.valueOf(f));
                    s.append(String.join("; ", fl)).append('\n');
                }
            }
            for (String f : findings(e)) s.append("- ").append(f.replaceAll("<[^>]+>", "")).append('\n');
            s.append('\n');
        }
        return s.toString();
    }

    // ================================================================================================ utilities

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Used by tests. */
    static Map<String, Object> emptyRun() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("experiments", List.of());
        return m;
    }

    private static final String CSS = """
            :root{--bg:#f6f7f9;--card:#fff;--fg:#1b1f24;--mut:#5b6573;--line:#e3e6eb;--ok:#13795b;--warn:#a15c00;--bad:#b42318;--okbg:#e3f5ee;--warnbg:#fdf0d9;--badbg:#fde8e6;--win:rgba(180,35,24,.08)}
            @media (prefers-color-scheme:dark){:root{--bg:#0f1216;--card:#171b21;--fg:#e8ebef;--mut:#9aa4b2;--line:#2a313a;--ok:#4fd1a1;--warn:#f0b35a;--bad:#ff8a7a;--okbg:#12332a;--warnbg:#3a2c10;--badbg:#3d1a16;--win:rgba(255,138,122,.10)}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
            main{max-width:1040px;margin:0 auto;padding:24px 16px 64px}
            h1{font-size:28px;margin:0}.sub{color:var(--mut);margin:2px 0 4px}.meta{color:var(--mut);font-size:13px;margin:0 0 20px}
            h2{font-size:20px;margin:0 0 4px}h3{font-size:14px;text-transform:uppercase;letter-spacing:.04em;color:var(--mut);margin:22px 0 8px}
            .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:20px;margin:0 0 20px}
            .q{font-weight:600;margin:0 0 8px}.note{color:var(--mut);font-size:13px}.small{color:var(--mut);font-size:12px;font-weight:400}
            code{background:var(--bg);padding:1px 5px;border-radius:4px;font-size:12px}
            .scroll{overflow-x:auto}table{border-collapse:collapse;width:100%}th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
            th{font-size:12px;color:var(--mut);font-weight:600}td.num{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}.res th:nth-child(n+3){text-align:right}
            .score td.g .chip{background:var(--okbg);color:var(--ok)}.score td.a .chip{background:var(--warnbg);color:var(--warn)}.score td.r .chip{background:var(--badbg);color:var(--bad)}
            .chip{display:inline-block;border-radius:12px;padding:1px 9px;margin:2px 4px 2px 0;font-size:12px}
            .v{display:inline-block;border-radius:4px;padding:1px 7px;font-size:11px;font-weight:700;letter-spacing:.04em}
            .v.worked{background:var(--okbg);color:var(--ok)}.v.partial{background:var(--warnbg);color:var(--warn)}.v.failed{background:var(--badbg);color:var(--bad)}.v.n\\/a{background:var(--line);color:var(--mut)}
            .fails{margin:4px 0 0;padding-left:16px}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:6px}
            .charts{display:grid;grid-template-columns:1fr 1fr;gap:20px}@media(max-width:860px){.charts{grid-template-columns:1fr}}
            .chart{width:100%;height:auto}.chart .grid{stroke:var(--line);stroke-width:1}.chart .lab{fill:var(--mut);font-size:11px}.chart .win{fill:var(--win)}
            .legend{display:flex;flex-wrap:wrap;gap:4px 14px;font-size:12px;color:var(--mut);margin:4px 0 0}.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px}
            .findings li,.faults li{margin:4px 0}details{border:1px solid var(--line);border-radius:8px;margin:8px 0;padding:8px 12px}summary{cursor:pointer;font-weight:600}
            .trace{margin:12px 0 4px;font-size:12px}.th{margin-bottom:4px}.row{display:grid;grid-template-columns:minmax(150px,34%) 1fr 130px;gap:8px;align-items:center;padding:1px 0}
            .lbl{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.bar{position:relative;height:10px;background:var(--bg);border-radius:3px}.bar i{position:absolute;top:0;height:10px;border-radius:3px}
            .bar i.ok{background:var(--ok)}.bar i.warn{background:var(--warn)}.bar i.bad{background:var(--bad)}.st{color:var(--mut);white-space:nowrap}.st.bad{color:var(--bad)}.st.warn{color:var(--warn)}
            ul{padding-left:20px}.res td:first-child{min-width:230px}.res td:nth-child(2){min-width:170px}.fails{list-style:none;padding-left:0}.fails li{margin:2px 0}
            """;
}

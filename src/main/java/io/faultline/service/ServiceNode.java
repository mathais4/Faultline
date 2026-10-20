package io.faultline.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.faultline.core.Hashing;
import io.faultline.core.Json;
import io.faultline.core.Metrics;
import io.faultline.fault.FaultInjector;
import io.faultline.fault.FaultSpec;
import io.faultline.fault.FaultType;
import io.faultline.trace.Span;
import io.faultline.trace.SpanStore;
import io.faultline.trace.TraceCtx;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared skeleton of every Faultline service: an HTTP listener, a control API under /_faultline/, fault
 * injection, bounded concurrency (so overload happens for real), and span/metric recording.
 *
 * Fault injection and admission control wrap business routes only. The control API is exempt so an experiment can
 * always reset and inspect a service, except while the service is deliberately "crashed" (listener closed).
 */
public abstract class ServiceNode implements AutoCloseable {

    /** Capacity model. Defaults are generous; scenarios shrink them to provoke overload. */
    public record Tuning(long serviceTimeMs, int maxConcurrent, int maxQueue, long maxQueueWaitMs)
            implements Json.Jsonable {
        public static final Tuning DEFAULT = new Tuning(5, 256, 512, 1000);

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serviceTimeMs", serviceTimeMs);
            m.put("maxConcurrent", maxConcurrent);
            m.put("maxQueue", maxQueue);
            m.put("maxQueueWaitMs", maxQueueWaitMs);
            return m;
        }

        public static Tuning fromJson(Map<String, Object> m) {
            if (m == null) return DEFAULT;
            return new Tuning(Json.lng(m, "serviceTimeMs", DEFAULT.serviceTimeMs),
                    Json.integer(m, "maxConcurrent", DEFAULT.maxConcurrent),
                    Json.integer(m, "maxQueue", DEFAULT.maxQueue),
                    Json.lng(m, "maxQueueWaitMs", DEFAULT.maxQueueWaitMs));
        }
    }

    /** A parsed inbound business request. */
    public static final class Request {
        public final String path, body, idempotencyKey;
        public final int attempt;
        public final TraceCtx ctx;
        private Map<String, Object> json;

        Request(String path, String body, String idempotencyKey, int attempt, TraceCtx ctx) {
            this.path = path;
            this.body = body;
            this.idempotencyKey = idempotencyKey;
            this.attempt = attempt;
            this.ctx = ctx;
        }

        public synchronized Map<String, Object> json() {
            if (json == null) json = body == null || body.isBlank() ? Map.of() : Json.parseObject(body);
            return json;
        }
    }

    public record Response(int status, String body) {
        public static Response of(int status, Map<String, Object> body) {
            return new Response(status, Json.write(body));
        }

        public static Response ok(Map<String, Object> body) {
            return of(200, body);
        }

        public static Response error(int status, String message) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", message);
            return of(status, m);
        }
    }

    protected final String name;
    protected final SpanStore spans;
    protected final FaultInjector faults = new FaultInjector();
    protected final Metrics metrics = new Metrics();
    protected volatile long seed = 1;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "faultline-scheduler-" + System.identityHashCode(r));
        t.setDaemon(true);
        return t;
    });
    private final List<ScheduledFuture<?>> crashTasks = new ArrayList<>();
    private volatile Tuning tuning = Tuning.DEFAULT;
    private volatile Semaphore permits = new Semaphore(Tuning.DEFAULT.maxConcurrent());
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger inflight = new AtomicInteger();

    private HttpServer server;
    private int port;
    private volatile boolean closed;

    protected ServiceNode(String name, int port) {
        this.name = name;
        this.port = port;
        this.spans = new SpanStore(name);
    }

    public String name() {
        return name;
    }

    public synchronized int port() {
        return port;
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    // ------------------------------------------------------------------ subclass hooks

    /** Business logic. May throw; a thrown exception becomes a 500. */
    protected abstract Response handle(Request req) throws Exception;

    /** Apply service-specific configuration (policy, queue config, ...). Called after faults are applied. */
    protected void onConfigure(Map<String, Object> cfg) {}

    /** Clear service-specific state. Called by reset. */
    protected void onReset() {}

    /** Ground-truth state exposed at /_faultline/ledger (what really happened, independent of what clients saw). */
    protected Map<String, Object> ledger() {
        return Map.of();
    }

    /** Extra fields merged into /_faultline/metrics. */
    protected Map<String, Object> extraMetrics() {
        return Map.of();
    }

    /** Extra control endpoints. Return a JSON string, or null if the path is not handled. */
    protected String extraControl(String path) {
        return null;
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        bind();
    }

    private void bind() throws IOException {
        if (server != null || closed) return;
        BindException last = null;
        for (int i = 0; i < 40; i++) {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(port), 1024);
                s.createContext("/", this::dispatch);
                s.setExecutor(executor);
                s.start();
                server = s;
                port = s.getAddress().getPort();
                return;
            } catch (BindException e) {
                last = e; // port still draining after a crash; retry briefly
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("could not bind");
    }

    private synchronized void stopListener() {
        if (server != null) {
            server.stop(0);
            server = null;
            metrics.inc("crashes");
        }
    }

    private synchronized void startListener() {
        try {
            bind();
        } catch (IOException e) {
            System.err.println("[" + name + "] failed to restart listener: " + e);
        }
    }

    public synchronized boolean isListening() {
        return server != null;
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        stopListener();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ control API

    private void dispatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/_faultline/")) {
            control(ex, path);
        } else {
            business(ex, path);
        }
    }

    private void control(HttpExchange ex, String path) throws IOException {
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object result;
            switch (path) {
                case "/_faultline/health" -> result = Map.of("service", name, "ok", true);
                case "/_faultline/configure" -> {
                    applyConfig(Json.parseObject(body));
                    result = Map.of("ok", true, "nowMs", System.currentTimeMillis());
                }
                case "/_faultline/reset" -> {
                    reset();
                    result = Map.of("ok", true);
                }
                case "/_faultline/metrics" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("service", name);
                    m.putAll(metrics.snapshot());
                    m.put("spansDropped", spans.dropped());
                    m.put("active", inflight.get() + waiting.get()); // requests being served or waiting for a permit
                    m.putAll(extraMetrics());
                    result = m;
                }
                case "/_faultline/spans" -> result = spans.snapshot();
                case "/_faultline/ledger" -> result = ledger();
                default -> {
                    String extra = extraControl(path);
                    if (extra == null) {
                        send(ex, 404, Json.write(Map.of("error", "unknown control path")));
                        return;
                    }
                    send(ex, 200, extra);
                    return;
                }
            }
            send(ex, 200, Json.write(result));
        } catch (IllegalArgumentException e) {
            send(ex, 400, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        } catch (RuntimeException e) {
            send(ex, 500, Json.write(Map.of("error", e.toString())));
        } finally {
            ex.close();
        }
    }

    /** Reset + (re)configure in one call. Keys: reset, seed, tuning, faults, plus subclass keys. */
    public void applyConfig(Map<String, Object> cfg) {
        if (Json.bool(cfg, "reset", false)) reset();
        if (cfg.containsKey("seed")) seed = Json.lng(cfg, "seed", 1);
        if (cfg.containsKey("tuning")) {
            Tuning t = Tuning.fromJson(Json.map(cfg, "tuning"));
            tuning = t;
            permits = new Semaphore(t.maxConcurrent());
        }
        if (cfg.containsKey("faults")) {
            List<FaultSpec> specs = new ArrayList<>();
            for (Object o : Json.list(cfg, "faults")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fm = (Map<String, Object>) o;
                specs.add(FaultSpec.fromJson(fm));
            }
            faults.configure(seed, specs);
            scheduleCrashes(specs);
        }
        onConfigure(cfg);
    }

    private synchronized void scheduleCrashes(List<FaultSpec> specs) {
        long now = System.currentTimeMillis();
        for (FaultSpec f : specs) {
            if (f.type() != FaultType.CRASH) continue;
            long stopIn = Math.max(0, f.fromMs() - now);
            crashTasks.add(scheduler.schedule(this::stopListener, stopIn, TimeUnit.MILLISECONDS));
            if (f.untilMs() > 0) {
                long startIn = Math.max(0, f.untilMs() - now);
                crashTasks.add(scheduler.schedule(this::startListener, startIn, TimeUnit.MILLISECONDS));
            }
        }
    }

    public synchronized void reset() {
        for (ScheduledFuture<?> t : crashTasks) t.cancel(false);
        crashTasks.clear();
        faults.clear();
        spans.clear();
        metrics.reset();
        tuning = Tuning.DEFAULT;
        permits = new Semaphore(Tuning.DEFAULT.maxConcurrent());
        startListener();
        onReset();
    }

    // ------------------------------------------------------------------ business pipeline

    private void business(HttpExchange ex, String path) throws IOException {
        long startMs = System.currentTimeMillis();
        long t0 = System.nanoTime();

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Headers h = ex.getRequestHeaders();
        int attempt = parseInt(h.getFirst(TraceCtx.H_ATTEMPT), 1);
        String idem = h.getFirst(TraceCtx.H_IDEMPOTENCY);
        String key = h.getFirst(TraceCtx.H_KEY);
        Request preliminary = new Request(path, body, idem, attempt, null);
        if (key == null) {
            try {
                key = Json.str(preliminary.json(), "orderId", "");
            } catch (IllegalArgumentException e) {
                send(ex, 400, Json.write(Map.of("error", "invalid JSON body")));
                ex.close();
                return;
            }
        }
        String traceId = h.getFirst(TraceCtx.H_TRACE);
        if (traceId == null) traceId = TraceCtx.traceIdFor(key);
        String parent = h.getFirst(TraceCtx.H_PARENT);
        String spanId = spans.newSpanId();
        TraceCtx inbound = new TraceCtx(traceId, parent == null ? "" : parent, key);
        // downstream calls made while handling this request are children of this span
        Request req = new Request(path, body, idem, attempt, inbound.withParent(spanId));

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("attempt", Integer.toString(attempt));
        String status = "ok";
        Semaphore held = null;
        metrics.inc("requests");

        try {
            held = admit();
            if (held == null) {
                metrics.inc("rejected_overload");
                status = "rejected";
                respond(ex, 503, "{\"error\":\"overloaded\"}");
                return;
            }
            metrics.peak("peak_inflight", inflight.incrementAndGet());

            FaultInjector.Decision d = faults.decide(path, key, attempt, startMs);
            if (!d.applied().isEmpty()) {
                tags.put("faults", String.join(",", d.applied()));
                for (String id : d.applied()) metrics.inc("fault." + id);
            }
            sleep(d.beforeDelayMs());
            if (d.dropBefore()) {
                status = "dropped";
                return; // finally closes the exchange without a response
            }
            if (d.errorStatus() > 0) {
                status = "error";
                respond(ex, d.errorStatus(), "{\"error\":\"injected fault\"}");
                metrics.inc("status." + d.errorStatus());
                return;
            }

            Tuning t = tuning;
            double jitter = 0.5 + Hashing.unit(seed, "service-time", name, key, Integer.toString(attempt));
            sleep((long) (t.serviceTimeMs() * jitter));

            Response r = handle(req);
            sleep(d.afterDelayMs());
            if (d.dropAfter()) {
                status = "dropped_after_work"; // side effect happened, caller never hears about it
                return;
            }
            respond(ex, r.status(), r.body());
            metrics.inc("status." + r.status());
            if (r.status() >= 500) status = "error";
            tags.put("http", Integer.toString(r.status()));
        } catch (Throwable t) {
            status = "error";
            tags.put("exception", t.toString());
            metrics.inc("exceptions");
            try {
                respond(ex, 500, Json.write(Map.of("error", String.valueOf(t.getMessage()))));
            } catch (IOException ignored) {
                // client already gone
            }
        } finally {
            if (held != null) {
                inflight.decrementAndGet();
                held.release();
            }
            double dur = (System.nanoTime() - t0) / 1e6;
            metrics.recordLatency(dur);
            spans.add(new Span(traceId, spanId, parent == null ? "" : parent, name, "POST " + path, startMs, dur,
                    status, tags));
            ex.close();
        }
    }

    /** @return the semaphore that was acquired (to release later) or null if the request was shed. */
    private Semaphore admit() {
        Tuning t = tuning;
        Semaphore s = permits;
        if (s.tryAcquire()) return s;
        if (waiting.incrementAndGet() > t.maxQueue()) {
            waiting.decrementAndGet();
            return null;
        }
        try {
            return s.tryAcquire(t.maxQueueWaitMs(), TimeUnit.MILLISECONDS) ? s : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            waiting.decrementAndGet();
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        try {
            send(ex, status, body);
        } catch (IOException e) {
            metrics.inc("client_gone"); // caller timed out and hung up; the work was already done
            throw e;
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    protected static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int parseInt(String s, int dflt) {
        try {
            return s == null ? dflt : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}

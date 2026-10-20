package io.faultline;

import io.faultline.core.Json;
import io.faultline.lab.Experiment;
import io.faultline.lab.ReportWriter;
import io.faultline.lab.Runner;
import io.faultline.lab.Scenarios;
import io.faultline.queue.InMemoryJobQueue;
import io.faultline.queue.JobQueue;
import io.faultline.queue.PostgresJobQueue;
import io.faultline.queue.QueueContract;
import io.faultline.service.Cluster;
import io.faultline.service.GatewayService;
import io.faultline.service.InventoryService;
import io.faultline.service.PaymentsService;
import io.faultline.service.ServiceNode;
import io.faultline.service.WorkerService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Command line entry point. Run with no arguments for usage. */
public final class Main {
    private Main() {}

    private static final String USAGE = """
            Faultline - distributed system failure lab

              faultline list                         list experiments
              faultline run [options]                run experiments, write results.json + report.html + report.md
              faultline report <results.json>        regenerate the report from saved results
              faultline check [options]              verify a queue implementation against the queue contract
              faultline node --role R [options]      run one service (payments|inventory|gateway|worker) for docker-compose

            run options
              --only a,b        experiment ids (default: all)
              --trials N        trials per variant (default 3)
              --quick           1 trial per variant (fast smoke run)
              --seed N          override every experiment's seed
              --out DIR         output directory (default out/<timestamp>)
              --connect g=URL,p=URL,i=URL,w=URL
                                use services already running (started with `faultline node`) instead of starting them

            queue backend (run, check, node)
              --queue memory|postgres      default: postgres if --db-url / FAULTLINE_DB_URL is set, else memory
              --db-url JDBC_URL            e.g. jdbc:postgresql://localhost:5432/faultline  (env FAULTLINE_DB_URL)
              --db-user USER               (env FAULTLINE_DB_USER, default faultline)
              --db-password PASSWORD       (env FAULTLINE_DB_PASSWORD, default faultline)

            node options
              --role R --port N --inventory-url URL --payments-url URL
            """;

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0 || argv[0].equals("-h") || argv[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        String cmd = argv[0];
        Map<String, String> opts = parse(argv);
        try {
            switch (cmd) {
                case "list" -> list();
                case "run" -> run(opts);
                case "report" -> report(argv, opts);
                case "check" -> System.exit(check(opts));
                case "node" -> node(opts);
                default -> {
                    System.err.println("unknown command: " + cmd + "\n");
                    System.err.print(USAGE);
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------------------------------------ commands

    private static void list() {
        for (Experiment e : Scenarios.all()) {
            System.out.printf("%-18s %s%n    %s%n    variants: %s%n", e.id(), e.title(), e.question(),
                    e.variants().stream().map(Experiment.Variant::id).toList());
        }
    }

    private static void run(Map<String, String> o) throws Exception {
        List<Experiment> exps = new ArrayList<>();
        if (o.containsKey("only")) {
            for (String id : o.get("only").split(",")) exps.add(Scenarios.byId(id.trim()));
        } else {
            exps.addAll(Scenarios.all());
        }
        int trials = o.containsKey("quick") ? 1 : Integer.parseInt(o.getOrDefault("trials", "3"));
        long seed = Long.parseLong(o.getOrDefault("seed", "0"));
        Path out = Path.of(o.getOrDefault("out", "out/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))));
        Files.createDirectories(out);

        Backend backend = backend(o);
        Cluster cluster = null;
        Cluster.Endpoints ep;
        try {
            if (o.containsKey("connect")) {
                ep = parseEndpoints(o.get("connect"));
                if (backend.label.equals("in-memory")) {
                    throw new IllegalArgumentException("--connect needs a shared queue: pass --queue postgres with --db-url");
                }
            } else {
                cluster = Cluster.start(backend.queue);
                ep = cluster.endpoints();
            }
            if (o.containsKey("connect")) awaitHealthy(ep);
            log("queue backend: " + backend.label + "; services: " + ep);
            Runner runner = new Runner(ep, backend.queue, Main::log);
            Map<String, Object> results = runner.run(exps, trials, seed, backend.label);

            Path json = out.resolve("results.json"), html = out.resolve("report.html"), md = out.resolve("report.md");
            Files.writeString(json, Json.write(results));
            // round-trip through the parser so the report is built from exactly what was saved
            Map<String, Object> saved = Json.parseObject(Files.readString(json));
            Files.writeString(html, ReportWriter.html(saved));
            Files.writeString(md, ReportWriter.markdown(saved));
            printSummary(saved);
            System.out.println("\nWrote " + html + "\n      " + md + "\n      " + json);
        } finally {
            if (cluster != null) cluster.close();
            backend.close();
        }
        System.exit(0);
    }

    private static void report(String[] argv, Map<String, String> o) throws IOException {
        if (argv.length < 2 || argv[1].startsWith("--")) throw new IllegalArgumentException("usage: faultline report <results.json>");
        Path in = Path.of(argv[1]);
        Path dir = Path.of(o.getOrDefault("out", in.toAbsolutePath().getParent().toString()));
        Map<String, Object> saved = Json.parseObject(Files.readString(in));
        Files.writeString(dir.resolve("report.html"), ReportWriter.html(saved));
        Files.writeString(dir.resolve("report.md"), ReportWriter.markdown(saved));
        System.out.println("Wrote " + dir.resolve("report.html") + " and report.md");
    }

    private static int check(Map<String, String> o) {
        Backend b = backend(o);
        try {
            System.out.println("Queue contract against: " + b.label);
            boolean ok = true;
            for (QueueContract.Result r : QueueContract.verify(b.queue)) {
                System.out.printf("  %s  %s%s%n", r.passed() ? "PASS" : "FAIL", r.name(), r.passed() ? "" : "  -> " + r.detail());
                ok &= r.passed();
            }
            System.out.println(ok ? "All checks passed." : "Some checks FAILED.");
            return ok ? 0 : 1;
        } finally {
            b.close();
        }
    }

    private static void node(Map<String, String> o) throws Exception {
        String role = o.get("role");
        if (role == null) throw new IllegalArgumentException("--role is required");
        int port = Integer.parseInt(o.getOrDefault("port", "8080"));
        ServiceNode node;
        Backend backend = null;
        switch (role) {
            case "payments" -> node = new PaymentsService(port);
            case "inventory" -> node = new InventoryService(port);
            case "gateway", "worker" -> {
                backend = backend(o);
                if (backend.label.equals("in-memory")) {
                    throw new IllegalArgumentException("a separate " + role + " process needs a shared queue: set FAULTLINE_DB_URL");
                }
                String inv = require(o, "inventory-url", "FAULTLINE_INVENTORY_URL");
                node = role.equals("worker") ? new WorkerService(port, inv, backend.queue)
                        : new GatewayService(port, inv, require(o, "payments-url", "FAULTLINE_PAYMENTS_URL"), backend.queue);
            }
            default -> throw new IllegalArgumentException("unknown role: " + role);
        }
        node.start();
        log(role + " listening on " + node.url());
        Thread.currentThread().join();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final class Backend implements AutoCloseable {
        final JobQueue queue;
        final String label;

        Backend(JobQueue q, String label) {
            this.queue = q;
            this.label = label;
        }

        @Override
        public void close() {
            if (queue instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // shutting down
                }
            }
        }
    }

    private static Backend backend(Map<String, String> o) {
        String url = o.getOrDefault("db-url", System.getenv("FAULTLINE_DB_URL"));
        String kind = o.getOrDefault("queue", url != null ? "postgres" : "memory");
        if (kind.equals("memory")) return new Backend(new InMemoryJobQueue(), "in-memory");
        if (!kind.equals("postgres")) throw new IllegalArgumentException("--queue must be memory or postgres");
        if (url == null) throw new IllegalArgumentException("--queue postgres needs --db-url or FAULTLINE_DB_URL");
        String user = o.getOrDefault("db-user", env("FAULTLINE_DB_USER", "faultline"));
        String pass = o.getOrDefault("db-password", env("FAULTLINE_DB_PASSWORD", "faultline"));
        return new Backend(new PostgresJobQueue(url, user, pass, 16), "PostgreSQL (" + url.replaceAll("//[^@/]*@", "//") + ")");
    }

    /** Block until every remote service answers its health check (compose starts them in no particular order). */
    private static void awaitHealthy(Cluster.Endpoints ep) throws InterruptedException {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        long deadline = System.currentTimeMillis() + 90_000;
        for (String url : List.of(ep.gateway(), ep.payments(), ep.inventory(), ep.worker())) {
            while (true) {
                try {
                    var r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "/_faultline/health"))
                            .timeout(java.time.Duration.ofSeconds(2)).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() == 200) break;
                } catch (IOException e) {
                    // not up yet
                }
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timed out waiting for " + url);
                Thread.sleep(500);
            }
        }
    }

    private static Cluster.Endpoints parseEndpoints(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split(",")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2) m.put(p[0].trim(), p[1].trim());
        }
        for (String k : List.of("gateway", "payments", "inventory", "worker")) {
            if (!m.containsKey(k)) throw new IllegalArgumentException("--connect needs gateway=,payments=,inventory=,worker= (missing " + k + ")");
        }
        return new Cluster.Endpoints(m.get("gateway"), m.get("payments"), m.get("inventory"), m.get("worker"));
    }

    private static String require(Map<String, String> o, String key, String envName) {
        String v = o.getOrDefault(key, System.getenv(envName));
        if (v == null) throw new IllegalArgumentException("--" + key + " (or " + envName + ") is required");
        return v;
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null ? dflt : v;
    }

    private static Map<String, String> parse(String[] argv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            int eq = key.indexOf('=');
            if (eq > 0) {
                m.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                m.put(key, argv[++i]);
            } else {
                m.put(key, "true");
            }
        }
        return m;
    }

    private static void log(String s) {
        System.err.println(s);
    }

    @SuppressWarnings("unchecked")
    private static void printSummary(Map<String, Object> run) {
        System.out.println("\n=== Verdicts ===");
        for (Object eo : Json.list(run, "experiments")) {
            Map<String, Object> e = (Map<String, Object>) eo;
            System.out.println("\n" + Json.str(e, "title", ""));
            for (Object vo : Json.list(e, "variants")) {
                Map<String, Object> v = (Map<String, Object>) vo;
                Map<String, Object> s = Json.map(v, "summary");
                System.out.printf("  %-8s %-40s success %5.1f%%  p99 %6.0fms%s%n",
                        Json.str(v, "verdict", "").toUpperCase(), Json.str(v, "label", ""),
                        Json.dbl(Json.map(s, "successRate"), "mean", 0) * 100, Json.dbl(Json.map(s, "p99"), "mean", 0),
                        Json.list(v, "failedChecks").isEmpty() ? "" : "   [" + String.join("; ", Json.list(v, "failedChecks").stream().map(String::valueOf).toList()) + "]");
            }
        }
    }
}

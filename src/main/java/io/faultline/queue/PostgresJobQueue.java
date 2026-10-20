package io.faultline.queue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

/**
 * PostgreSQL-backed queue using {@code FOR UPDATE SKIP LOCKED} for contention-free claiming.
 *
 * All timing uses the database clock (clock_timestamp()), so workers and producers on different machines agree on
 * visibility without clock sync. Requires the PostgreSQL JDBC driver on the classpath at runtime; the code itself
 * only touches java.sql interfaces.
 */
public final class PostgresJobQueue implements JobQueue, AutoCloseable {

    /** Unchecked wrapper so the JobQueue interface stays free of SQLException. */
    public static final class QueueException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public QueueException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    @FunctionalInterface
    private interface SqlFn<T> {
        T apply(Connection c) throws SQLException;
    }

    /** Arbitrary application-wide key for the schema-creation advisory lock. */
    private static final long SCHEMA_LOCK_ID = 7272701L;

    private final String url, user, password;
    private final Semaphore permits;
    private final ConcurrentLinkedDeque<Connection> idle = new ConcurrentLinkedDeque<>();

    public PostgresJobQueue(String url, String user, String password, int poolSize) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.permits = new Semaphore(poolSize);
        initSchema();
    }

    // ---------------------------------------------------------------- connection pool

    private Connection open() throws SQLException {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("No suitable driver")) {
                throw new SQLException("PostgreSQL JDBC driver not on classpath. Build with Maven "
                        + "(the pom declares org.postgresql:postgresql) or add the driver jar to -cp.", e);
            }
            throw e;
        }
    }

    private <T> T withConnection(SqlFn<T> fn) {
        permits.acquireUninterruptibly();
        Connection c = null;
        boolean broken = false;
        try {
            c = idle.poll();
            if (c == null || !c.isValid(1)) {
                if (c != null) closeQuietly(c);
                c = open();
            }
            return fn.apply(c);
        } catch (SQLException e) {
            broken = true;
            throw new QueueException("queue operation failed: " + e.getMessage(), e);
        } finally {
            if (c != null) {
                if (broken) closeQuietly(c);
                else idle.push(c);
            }
            permits.release();
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing useful to do
        }
    }

    private void initSchema() {
        String ddl;
        try (InputStream in = PostgresJobQueue.class.getResourceAsStream("/io/faultline/schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql missing from classpath");
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // Strip comments first (they may contain semicolons), then split into statements.
        String sql = ddl.replaceAll("(?m)--.*$", "");
        withConnection(c -> {
            try (Statement st = c.createStatement()) {
                // Gateway, worker and the lab all start at once under docker compose. Concurrent CREATE TABLE IF NOT
                // EXISTS can still fail with a catalog unique violation, so serialise the DDL with an advisory lock
                // (held on this connection, released explicitly and by the server if the connection dies).
                st.execute("SELECT pg_advisory_lock(" + SCHEMA_LOCK_ID + ")");
                try {
                    for (String stmt : sql.split(";")) {
                        String s = stmt.trim();
                        if (!s.isEmpty()) st.execute(s);
                    }
                } finally {
                    st.execute("SELECT pg_advisory_unlock(" + SCHEMA_LOCK_ID + ")");
                }
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- JobQueue

    @Override
    public Optional<Long> enqueue(String orderKey, String dedupKey, String payload, int maxAttempts) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO jobs (order_key, dedup_key, payload, max_attempts) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (dedup_key) DO NOTHING RETURNING id")) {
                ps.setString(1, orderKey);
                ps.setString(2, dedupKey);
                ps.setString(3, payload);
                ps.setInt(4, maxAttempts);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty();
                }
            }
        });
    }

    @Override
    public List<Job> claim(String workerId, long visibilityMs, int limit) {
        return withConnection(c -> {
            // 1. Dead-letter jobs that ran out of attempts and whose last visibility window has lapsed.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE jobs SET state = 'dead', last_error = COALESCE(last_error, 'visibility timeout on final attempt') "
                            + "WHERE state IN ('queued', 'running') AND visible_at <= clock_timestamp() "
                            + "AND attempts >= max_attempts")) {
                ps.executeUpdate();
            }
            // 2. Claim. SKIP LOCKED lets concurrent workers pass over rows another worker is claiming.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE jobs SET state = 'running', attempts = attempts + 1, claim_token = gen_random_uuid(), "
                            + "claimed_by = ?, visible_at = clock_timestamp() + (? * interval '1 millisecond') "
                            + "WHERE id IN (SELECT id FROM jobs WHERE state IN ('queued', 'running') "
                            + "AND visible_at <= clock_timestamp() AND attempts < max_attempts "
                            + "ORDER BY visible_at, id FOR UPDATE SKIP LOCKED LIMIT ?) "
                            + "RETURNING id, order_key, payload, attempts, max_attempts, claim_token::text")) {
                ps.setString(1, workerId);
                ps.setLong(2, visibilityMs);
                ps.setInt(3, limit);
                List<Job> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                                rs.getString(6)));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public boolean ack(Job job) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE jobs SET state = 'done', completed_at = clock_timestamp() "
                            + "WHERE id = ? AND claim_token = ?::uuid AND state = 'running'")) {
                ps.setLong(1, job.id());
                ps.setString(2, job.claimToken());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean nack(Job job, long retryDelayMs, String error) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE jobs SET state = CASE WHEN attempts >= max_attempts THEN 'dead' ELSE 'queued' END, "
                            + "visible_at = clock_timestamp() + (? * interval '1 millisecond'), last_error = ? "
                            + "WHERE id = ? AND claim_token = ?::uuid AND state = 'running'")) {
                ps.setLong(1, retryDelayMs);
                ps.setString(2, error);
                ps.setLong(3, job.id());
                ps.setString(4, job.claimToken());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public QueueStats stats() {
        return withConnection(c -> {
            Map<String, Long> by = new LinkedHashMap<>();
            for (String s : List.of("queued", "running", "done", "dead")) by.put(s, 0L);
            Map<String, Integer> deliveries = new HashMap<>();
            Map<String, Integer> enqueues = new HashMap<>();
            try (Statement st = c.createStatement()) {
                // Exhausted jobs whose visibility lapsed are dead even if no claim has swept them yet.
                try (ResultSet rs = st.executeQuery(
                        "SELECT CASE WHEN state IN ('queued','running') AND attempts >= max_attempts "
                                + "AND visible_at <= clock_timestamp() THEN 'dead' ELSE state END AS s, count(*) "
                                + "FROM jobs GROUP BY 1")) {
                    while (rs.next()) by.put(rs.getString(1), rs.getLong(2));
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT order_key, sum(attempts)::int, count(*)::int FROM jobs GROUP BY order_key")) {
                    while (rs.next()) {
                        deliveries.put(rs.getString(1), rs.getInt(2));
                        enqueues.put(rs.getString(1), rs.getInt(3));
                    }
                }
            }
            return new QueueStats(by, deliveries, enqueues);
        });
    }

    @Override
    public void reset() {
        withConnection(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("TRUNCATE jobs RESTART IDENTITY");
            }
            return null;
        });
    }

    @Override
    public void close() {
        Connection c;
        while ((c = idle.poll()) != null) closeQuietly(c);
    }
}

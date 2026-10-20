package io.faultline.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * In-process implementation of the same semantics as {@link PostgresJobQueue}. Used for Docker-free runs and
 * unit checks. Reports produced with it are labelled "in-memory queue": it models Postgres, it does not measure it.
 */
public final class InMemoryJobQueue implements JobQueue {

    private enum State { QUEUED, RUNNING, DONE, DEAD }

    private static final class Row {
        long id;
        String orderKey, payload, claimToken;
        State state = State.QUEUED;
        int attempts, maxAttempts;
        long visibleAt;
    }

    private final LongSupplier clock;
    private final Object lock = new Object();
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> dedup = new HashSet<>();
    private final Map<String, Integer> deliveries = new HashMap<>();
    private final Map<String, Integer> enqueues = new HashMap<>();
    private long nextId = 1;

    public InMemoryJobQueue() {
        this(System::currentTimeMillis);
    }

    public InMemoryJobQueue(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Long> enqueue(String orderKey, String dedupKey, String payload, int maxAttempts) {
        synchronized (lock) {
            if (dedupKey != null && !dedup.add(dedupKey)) return Optional.empty();
            Row r = new Row();
            r.id = nextId++;
            r.orderKey = orderKey;
            r.payload = payload;
            r.maxAttempts = maxAttempts;
            r.visibleAt = clock.getAsLong();
            rows.add(r);
            enqueues.merge(orderKey, 1, Integer::sum);
            return Optional.of(r.id);
        }
    }

    @Override
    public List<Job> claim(String workerId, long visibilityMs, int limit) {
        long now = clock.getAsLong();
        List<Job> out = new ArrayList<>();
        synchronized (lock) {
            for (Row r : rows) {
                if (r.state != State.QUEUED && r.state != State.RUNNING) continue;
                if (r.visibleAt > now) continue;
                if (r.attempts >= r.maxAttempts) {
                    r.state = State.DEAD; // visibility lapsed on the last allowed attempt: give up
                    continue;
                }
                if (out.size() >= limit) continue;
                r.state = State.RUNNING;
                r.attempts++;
                r.claimToken = UUID.randomUUID().toString();
                r.visibleAt = now + visibilityMs;
                deliveries.merge(r.orderKey, 1, Integer::sum);
                out.add(new Job(r.id, r.orderKey, r.payload, r.attempts, r.maxAttempts, r.claimToken));
            }
        }
        return out;
    }

    @Override
    public boolean ack(Job job) {
        synchronized (lock) {
            Row r = find(job.id());
            if (r == null || r.state != State.RUNNING || !job.claimToken().equals(r.claimToken)) return false;
            r.state = State.DONE;
            return true;
        }
    }

    @Override
    public boolean nack(Job job, long retryDelayMs, String error) {
        synchronized (lock) {
            Row r = find(job.id());
            if (r == null || r.state != State.RUNNING || !job.claimToken().equals(r.claimToken)) return false;
            if (r.attempts >= r.maxAttempts) {
                r.state = State.DEAD;
            } else {
                r.state = State.QUEUED;
                r.visibleAt = clock.getAsLong() + retryDelayMs;
            }
            return true;
        }
    }

    private Row find(long id) {
        // ids are dense and 1-based while no rows are removed
        int idx = (int) (id - 1);
        return idx >= 0 && idx < rows.size() && rows.get(idx).id == id ? rows.get(idx) : null;
    }

    @Override
    public QueueStats stats() {
        synchronized (lock) {
            long now = clock.getAsLong();
            Map<String, Long> by = new LinkedHashMap<>();
            for (String s : List.of("queued", "running", "done", "dead")) by.put(s, 0L);
            for (Row r : rows) {
                State s = r.state;
                // an exhausted job whose visibility lapsed is dead even if no claim has swept it yet
                if ((s == State.QUEUED || s == State.RUNNING) && r.attempts >= r.maxAttempts && r.visibleAt <= now) {
                    s = State.DEAD;
                }
                by.merge(s.name().toLowerCase(), 1L, Long::sum);
            }
            return new QueueStats(by, new HashMap<>(deliveries), new HashMap<>(enqueues));
        }
    }

    @Override
    public void reset() {
        synchronized (lock) {
            rows.clear();
            dedup.clear();
            deliveries.clear();
            enqueues.clear();
            nextId = 1;
        }
    }
}

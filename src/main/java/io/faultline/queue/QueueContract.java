package io.faultline.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Executable specification of {@link JobQueue}. Run it against any implementation, including your own queue
 * adapter, before trusting experiment results produced with it. {@code faultline check} runs it.
 */
public final class QueueContract {
    private QueueContract() {}

    public record Result(String name, boolean passed, String detail) {}

    private static final long VIS = 250;

    public static List<Result> verify(JobQueue q) {
        List<Result> out = new ArrayList<>();
        run(out, "enqueue assigns an id", () -> {
            q.reset();
            return q.enqueue("o1", null, "{}", 3).isPresent() ? null : "no id returned";
        });
        run(out, "dedup key drops the second enqueue", () -> {
            q.reset();
            q.enqueue("o1", "d1", "{}", 3);
            Optional<Long> second = q.enqueue("o1", "d1", "{}", 3);
            return second.isEmpty() ? null : "duplicate was accepted";
        });
        run(out, "claim hides the job for the visibility timeout", () -> {
            q.reset();
            q.enqueue("o1", null, "{}", 3);
            if (q.claim("w", VIS, 1).size() != 1) return "first claim returned nothing";
            return q.claim("w", VIS, 1).isEmpty() ? null : "job was claimable while hidden";
        });
        run(out, "unacked job is redelivered with attempts+1 and a new token", () -> {
            q.reset();
            q.enqueue("o1", null, "{}", 3);
            Job a = q.claim("w", VIS, 1).get(0);
            sleep(VIS + 100);
            List<Job> again = q.claim("w", VIS, 1);
            if (again.size() != 1) return "no redelivery after visibility expired";
            Job b = again.get(0);
            if (b.attempts() != a.attempts() + 1) return "attempts did not increment";
            return b.claimToken().equals(a.claimToken()) ? "claim token was reused" : null;
        });
        run(out, "stale ack is rejected (fencing), current ack accepted", () -> {
            q.reset();
            q.enqueue("o1", null, "{}", 3);
            Job a = q.claim("w1", VIS, 1).get(0);
            sleep(VIS + 100);
            Job b = q.claim("w2", VIS, 1).get(0);
            if (q.ack(a)) return "stale ack was accepted";
            return q.ack(b) ? null : "current ack was rejected";
        });
        run(out, "acked job is never delivered again", () -> {
            q.reset();
            q.enqueue("o1", null, "{}", 3);
            q.ack(q.claim("w", VIS, 1).get(0));
            sleep(VIS + 100);
            return q.claim("w", VIS, 1).isEmpty() && q.stats().byState().get("done") == 1 ? null
                    : "done job was redelivered";
        });
        run(out, "nack releases the job after its retry delay", () -> {
            q.reset();
            q.enqueue("o1", null, "{}", 3);
            Job a = q.claim("w", 5_000, 1).get(0);
            q.nack(a, 150, "boom");
            if (!q.claim("w", VIS, 1).isEmpty()) return "job visible before the nack delay passed";
            sleep(250);
            return q.claim("w", VIS, 1).size() == 1 ? null : "job not visible after the nack delay";
        });
        run(out, "job is dead-lettered once max attempts are used", () -> {
            q.reset();
            q.enqueue("o1", null, "{}", 2);
            q.nack(q.claim("w", VIS, 1).get(0), 0, "e1");
            q.nack(q.claim("w", VIS, 1).get(0), 0, "e2");
            sleep(50);
            if (!q.claim("w", VIS, 1).isEmpty()) return "dead job was delivered a third time";
            return q.stats().byState().get("dead") == 1 ? null : "job not counted as dead";
        });
        run(out, "visibility lapse on the final attempt dead-letters the job", () -> {
            q.reset();
            q.enqueue("o1", null, "{}", 1);
            q.claim("w", VIS, 1);
            sleep(VIS + 100);
            if (!q.claim("w", VIS, 1).isEmpty()) return "job delivered beyond max attempts";
            return q.stats().byState().get("dead") == 1 ? null : "job not counted as dead";
        });
        run(out, "concurrent claims never hand one job to two workers", () -> {
            q.reset();
            for (int i = 0; i < 40; i++) q.enqueue("o" + i, null, "{}", 3);
            List<Long> seen = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> ts = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                String id = "w" + w;
                ts.add(Thread.ofPlatform().start(() -> {
                    List<Job> got;
                    while (!(got = q.claim(id, 10_000, 3)).isEmpty()) got.forEach(j -> seen.add(j.id()));
                }));
            }
            for (Thread t : ts) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long distinct = seen.stream().distinct().count();
            return seen.size() == 40 && distinct == 40 ? null
                    : "claimed " + seen.size() + " times, " + distinct + " distinct of 40";
        });
        q.reset();
        return out;
    }

    private static void run(List<Result> out, String name, Supplier<String> check) {
        try {
            String failure = check.get();
            out.add(new Result(name, failure == null, failure == null ? "ok" : failure));
        } catch (RuntimeException e) {
            out.add(new Result(name, false, "threw " + e));
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

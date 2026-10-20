package io.faultline.queue;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QueueContractTest {
    @Test
    public void inMemoryQueueSatisfiesTheContract() {
        for (QueueContract.Result r : QueueContract.verify(new InMemoryJobQueue())) {
            assertTrue(r.name() + ": " + r.detail(), r.passed());
        }
    }

    /** Runs only when FAULTLINE_DB_URL points at a PostgreSQL with the JDBC driver on the classpath. */
    @Test
    public void postgresQueueSatisfiesTheContract() {
        String url = System.getenv("FAULTLINE_DB_URL");
        org.junit.Assume.assumeNotNull(url);
        try (PostgresJobQueue q = new PostgresJobQueue(url,
                System.getenv().getOrDefault("FAULTLINE_DB_USER", "faultline"),
                System.getenv().getOrDefault("FAULTLINE_DB_PASSWORD", "faultline"), 8)) {
            for (QueueContract.Result r : QueueContract.verify(q)) {
                assertTrue(r.name() + ": " + r.detail(), r.passed());
            }
        }
    }
}

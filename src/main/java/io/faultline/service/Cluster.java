package io.faultline.service;

import io.faultline.queue.JobQueue;
import java.io.IOException;

/**
 * Starts the four cooperating services in this JVM, each with its own HTTP listener on its own port, sharing one
 * job queue. Experiments only ever talk to them over HTTP, so the same runner also works against services started
 * separately with {@code faultline node} (see docker-compose.yml).
 */
public final class Cluster implements AutoCloseable {

    /** Where the runner finds each service. */
    public record Endpoints(String gateway, String payments, String inventory, String worker) {}

    public final PaymentsService payments;
    public final InventoryService inventory;
    public final GatewayService gateway;
    public final WorkerService worker;

    private Cluster(PaymentsService p, InventoryService i, GatewayService g, WorkerService w) {
        this.payments = p;
        this.inventory = i;
        this.gateway = g;
        this.worker = w;
    }

    public static Cluster start(JobQueue queue) throws IOException {
        PaymentsService payments = new PaymentsService(0);
        InventoryService inventory = new InventoryService(0);
        payments.start();
        inventory.start();
        GatewayService gateway = new GatewayService(0, inventory.url(), payments.url(), queue);
        WorkerService worker = new WorkerService(0, inventory.url(), queue);
        gateway.start();
        worker.start();
        return new Cluster(payments, inventory, gateway, worker);
    }

    public Endpoints endpoints() {
        return new Endpoints(gateway.url(), payments.url(), inventory.url(), worker.url());
    }

    @Override
    public void close() {
        gateway.close();
        worker.close();
        payments.close();
        inventory.close();
    }
}

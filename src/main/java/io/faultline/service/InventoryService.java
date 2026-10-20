package io.faultline.service;

import io.faultline.core.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stock reservation (/reserve, naturally idempotent) and shipment (/commit). Shipping twice is a real-world side
 * effect, so /commit is the target for duplicate-delivery experiments: it only de-duplicates when the caller sends
 * an Idempotency-Key.
 */
public final class InventoryService extends ServiceNode {
    private final Ledger reservations = new Ledger();
    private final Ledger shipments = new Ledger();
    private final IdempotencyStore idem = new IdempotencyStore();

    public InventoryService(int port) {
        super("inventory", port);
    }

    @Override
    protected Response handle(Request req) throws Exception {
        String orderId = Json.str(req.json(), "orderId", "");
        if (orderId.isEmpty()) return Response.error(400, "orderId required");
        switch (req.path) {
            case "/reserve" -> {
                reservations.record(orderId);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("orderId", orderId);
                out.put("reserved", true);
                return Response.ok(out);
            }
            case "/commit" -> {
                if (req.idempotencyKey != null) {
                    boolean[] replayed = {false};
                    Response r = idem.execute(req.idempotencyKey, () -> ship(orderId), replayed);
                    if (replayed[0]) metrics.inc("idempotent_replays");
                    return r;
                }
                return ship(orderId);
            }
            default -> {
                return Response.error(404, "not found");
            }
        }
    }

    private Response ship(String orderId) {
        int n = shipments.record(orderId);
        metrics.inc("shipments_executed");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("shipmentNumber", n);
        return Response.ok(out);
    }

    @Override
    protected Map<String, Object> ledger() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reservations", reservations.toJson());
        m.put("shipments", shipments.toJson());
        return m;
    }

    @Override
    protected void onReset() {
        reservations.clear();
        shipments.clear();
        idem.clear();
    }
}

package io.faultline.service;

import io.faultline.core.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Charges a customer. The side effect is "money moved", and doing it twice for one order is the canonical
 * distributed-systems bug: a retried request after a lost response charges the customer again, unless the request
 * carries an Idempotency-Key that the server honours.
 */
public final class PaymentsService extends ServiceNode {
    private final Ledger charges = new Ledger();
    private final IdempotencyStore idem = new IdempotencyStore();

    public PaymentsService(int port) {
        super("payments", port);
    }

    @Override
    protected Response handle(Request req) throws Exception {
        if (!req.path.equals("/charge")) return Response.error(404, "not found");
        String orderId = Json.str(req.json(), "orderId", "");
        if (orderId.isEmpty()) return Response.error(400, "orderId required");

        if (req.idempotencyKey != null) {
            boolean[] replayed = {false};
            Response r = idem.execute(req.idempotencyKey, () -> charge(orderId), replayed);
            if (replayed[0]) metrics.inc("idempotent_replays");
            return r;
        }
        return charge(orderId);
    }

    private Response charge(String orderId) {
        int n = charges.record(orderId);
        metrics.inc("charges_executed");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("chargeNumber", n);
        return Response.ok(out);
    }

    @Override
    protected Map<String, Object> ledger() {
        return Map.of("charges", charges.toJson());
    }

    @Override
    protected void onReset() {
        charges.clear();
        idem.clear();
    }
}

package io.faultline.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Server-side half of idempotency keys: the first request with a key performs the side effect; later requests with
 * the same key (including ones arriving while the first is still running) wait for and replay its result.
 */
final class IdempotencyStore {
    private final ConcurrentHashMap<String, CompletableFuture<ServiceNode.Response>> results = new ConcurrentHashMap<>();

    /** @param replayed set to true if this call returned a previously computed result instead of executing */
    ServiceNode.Response execute(String key, Supplier<ServiceNode.Response> effect, boolean[] replayed)
            throws Exception {
        CompletableFuture<ServiceNode.Response> mine = new CompletableFuture<>();
        CompletableFuture<ServiceNode.Response> existing = results.putIfAbsent(key, mine);
        if (existing != null) {
            replayed[0] = true;
            return existing.get(10, TimeUnit.SECONDS);
        }
        try {
            ServiceNode.Response r = effect.get();
            mine.complete(r);
            return r;
        } catch (RuntimeException e) {
            results.remove(key, mine); // failed attempts must not poison the key
            mine.completeExceptionally(e);
            throw e;
        }
    }

    void clear() {
        results.clear();
    }
}

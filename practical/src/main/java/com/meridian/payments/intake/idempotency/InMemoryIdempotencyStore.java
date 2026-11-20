package com.meridian.payments.intake.idempotency;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, List<String>> attemptsByKey = new ConcurrentHashMap<>();

    @Override
    public boolean isAccepted(String idempotencyKey) {
        return attemptsByKey.containsKey(idempotencyKey);
    }

    @Override
    public void markAccepted(String idempotencyKey, String providerEventId) {
        attemptsByKey.computeIfAbsent(idempotencyKey, k -> new CopyOnWriteArrayList<>()).add(providerEventId);
    }

    @Override
    public void recordDuplicateAttempt(String idempotencyKey, String providerEventId) {
        attemptsByKey.computeIfAbsent(idempotencyKey, k -> new CopyOnWriteArrayList<>()).add(providerEventId);
    }

    public List<String> attempts(String idempotencyKey) {
        return List.copyOf(attemptsByKey.getOrDefault(idempotencyKey, List.of()));
    }

    public void clear() {
        attemptsByKey.clear();
    }
}

package com.meridian.payments.intake.idempotency;

public interface IdempotencyStore {

    boolean isAccepted(String idempotencyKey);

    void markAccepted(String idempotencyKey, String providerEventId);

    void recordDuplicateAttempt(String idempotencyKey, String providerEventId);
}

package com.meridian.payments.intake.canonical;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CanonicalPaymentEvent(
        String schemaVersion,
        UUID eventId,
        String idempotencyKey,
        String correlationId,
        Country country,
        Provider provider,
        String providerEventId,
        String providerTransactionId,
        String paymentKey,
        String policyNumber,
        String rawPolicyReference,
        PaymentStatus status,
        String providerStatus,
        Instant statusChangedAt,
        Instant receivedAt,
        String amount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refundedAmount,
        String currency,
        PaymentMethod paymentMethod,
        @JsonInclude(JsonInclude.Include.NON_NULL) String statusReason) {

    public static final String SCHEMA_VERSION = "1.0";

    public CanonicalPaymentEvent {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static String paymentKey(Provider provider, String providerTransactionId) {
        return provider.name() + ":" + providerTransactionId;
    }
    public static UUID eventIdFor(String idempotencyKey) {
        return UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
    }
}

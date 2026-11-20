package com.meridian.payments.intake.core;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meridian.payments.intake.canonical.CanonicalPaymentEvent;
import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.idempotency.IdempotencyStore;
import com.meridian.payments.intake.messaging.EventPublisher;
import com.meridian.payments.intake.messaging.OutboundMessage;
import com.meridian.payments.intake.provider.MappingContext;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.PayloadMappingException;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.SignatureVerificationException;
import com.meridian.payments.intake.provider.WebhookRequest;

public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final Map<Provider, ProviderAdapter> adapters;
    private final EventPublisher publisher;
    private final IdempotencyStore idempotencyStore;
    private final ExecutorService publishExecutor;
    private final IntakeProperties properties;
    private final Clock clock;
    private final ObjectMapper eventMapper;

    public IntakeService(List<ProviderAdapter> adapters,
                         EventPublisher publisher,
                         IdempotencyStore idempotencyStore,
                         ExecutorService publishExecutor,
                         IntakeProperties properties,
                         Clock clock) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ProviderAdapter::provider, Function.identity()));
        this.publisher = publisher;
        this.idempotencyStore = idempotencyStore;
        this.publishExecutor = publishExecutor;
        this.properties = properties;
        this.clock = clock;
        this.eventMapper = canonicalEventMapper();
    }

    public static ObjectMapper canonicalEventMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public IntakeOutcome handle(String providerPath, WebhookRequest request, String correlationId) {
        Optional<ProviderAdapter> adapter = Provider.fromPathId(providerPath).map(adapters::get);
        if (adapter.isEmpty()) {
            log.warn("Webhook for unknown provider path={}", sanitize(providerPath));
            return IntakeOutcome.UNKNOWN_PROVIDER;
        }
        Provider provider = adapter.get().provider();

        if (request.rawBody().length > properties.maxBodyBytes()) {
            log.warn("Webhook rejected provider={} reason=payload_too_large bytes={}", provider, request.rawBody().length);
            return IntakeOutcome.PAYLOAD_TOO_LARGE;
        }

        try {
            adapter.get().verifySignature(request);
        } catch (SignatureVerificationException e) {
            log.warn("Webhook rejected provider={} reason=invalid_signature detail=\"{}\"", provider, e.getMessage());
            return IntakeOutcome.INVALID_SIGNATURE;
        }

        MappingResult result;
        try {
            Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            result = adapter.get().map(request, new MappingContext(correlationId, receivedAt));
        } catch (PayloadMappingException e) {
            log.error("Authentic webhook could not be mapped provider={} reason=\"{}\"", provider, e.getMessage());
            return IntakeOutcome.UNPROCESSABLE;
        }

        if (result instanceof MappingResult.Ignored ignored) {
            log.info("Webhook ignored provider={} providerEventId={} reason=\"{}\"",
                    provider, ignored.providerEventId(), ignored.reason());
            return IntakeOutcome.IGNORED;
        }
        CanonicalPaymentEvent event = ((MappingResult.Mapped) result).event();

        if (alreadyAccepted(event)) {
            safely(() -> idempotencyStore.recordDuplicateAttempt(event.idempotencyKey(), event.providerEventId()));
            log.info("Duplicate webhook provider={} idempotencyKey={} providerEventId={}",
                    provider, event.idempotencyKey(), event.providerEventId());
            return IntakeOutcome.DUPLICATE;
        }

        try {
            publishWithinBudget(toMessage(event));
        } catch (PublishFailure e) {
            log.error("Webhook not accepted, broker unavailable provider={} idempotencyKey={} reason={}",
                    provider, event.idempotencyKey(), e.getMessage());
            return IntakeOutcome.UNAVAILABLE;
        }

        safely(() -> idempotencyStore.markAccepted(event.idempotencyKey(), event.providerEventId()));
        log.info("Webhook accepted provider={} country={} idempotencyKey={} providerEventId={} transactionId={} status={} policyResolved={}",
                provider, event.country(), event.idempotencyKey(), event.providerEventId(),
                event.providerTransactionId(), event.status(), event.policyNumber() != null);
        return IntakeOutcome.ACCEPTED;
    }
    
    /** Fail-open: a duplicate is better than a lost payment. */
    private boolean alreadyAccepted(CanonicalPaymentEvent event) {
        try {
            return idempotencyStore.isAccepted(event.idempotencyKey());
        } catch (RuntimeException e) {
            log.warn("Idempotency store unavailable, publishing anyway idempotencyKey={} error={}",
                    event.idempotencyKey(), e.getClass().getSimpleName());
            return false;
        }
    }

    private void publishWithinBudget(OutboundMessage message) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> publisher.publish(message), publishExecutor);
        try {
            future.get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PublishFailure("timeout after " + properties.publishTimeout().toMillis() + " ms");
        } catch (ExecutionException e) {
            throw new PublishFailure(e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailure("interrupted");
        }
    }

    private OutboundMessage toMessage(CanonicalPaymentEvent event) {
        try {
            return new OutboundMessage(
                    event.idempotencyKey(),
                    event.correlationId(),
                    eventMapper.writeValueAsBytes(event),
                    Map.of(
                            "schemaVersion", event.schemaVersion(),
                            "country", event.country().name(),
                            "provider", event.provider().name(),
                            "status", event.status().name()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical event serialization failed", e);
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Idempotency store update failed error={}", e.getClass().getSimpleName());
        }
    }

    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static final class PublishFailure extends RuntimeException {
        PublishFailure(String message) {
            super(message);
        }
    }
}

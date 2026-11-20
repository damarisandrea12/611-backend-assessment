package com.meridian.payments.intake.core;

import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED;
import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED_RETRY;
import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_REVERSED;
import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.meridian.payments.intake.idempotency.IdempotencyStore;
import com.meridian.payments.intake.idempotency.InMemoryIdempotencyStore;
import com.meridian.payments.intake.messaging.EventPublisher;
import com.meridian.payments.intake.messaging.InMemoryEventPublisher;
import com.meridian.payments.intake.messaging.OutboundMessage;
import com.meridian.payments.intake.messaging.PublishException;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.provider.andina.AndinaSignatureVerifier;
import com.meridian.payments.intake.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.provider.quetzal.QuetzalSignatureVerifier;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.ProviderSamples;
import com.meridian.payments.intake.support.ProviderSamples.Sample;

@DisplayName("Shared intake flow: signature -> map -> dedup -> durable publish -> 2xx")
class IntakeServiceTest {

    private final MutableClock clock = new MutableClock(ANDINA_APPROVED.signedAt());
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final InMemoryEventPublisher broker = new InMemoryEventPublisher();
    private final IntakeProperties properties = new IntakeProperties(Duration.ofMillis(500), 65_536);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private IntakeService service(EventPublisher publisher, IdempotencyStore idempotencyStore) {
        return new IntakeService(
                List.of(new QuetzalAdapter(new QuetzalSignatureVerifier(ProviderSamples.testSecrets(), clock)),
                        new AndinaAdapter(new AndinaSignatureVerifier(ProviderSamples.testSecrets(), clock))),
                publisher, idempotencyStore, executor, properties, clock);
    }

    private IntakeOutcome deliver(IntakeService service, String provider, Sample sample) {
        clock.set(sample.signedAt().plusSeconds(1));
        return service.handle(provider, sample.request(), "corr-" + sample.file());
    }

    @Nested
    @DisplayName("FR4 - duplicates and retries")
    class Duplicates {

        @Test
        @DisplayName("Andina retry with a new notificacion_id is accepted (200) but published only once")
        void andinaRetryIsPublishedOnce() {
            IntakeService service = service(broker, store);

            assertThat(deliver(service, "andina", ANDINA_APPROVED)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(deliver(service, "andina", ANDINA_APPROVED_RETRY)).isEqualTo(IntakeOutcome.DUPLICATE);

            assertThat(broker.published()).hasSize(1);
            assertThat(broker.published().get(0).messageId()).isEqualTo("ANDINA:TRX-CO-5521907:APROBADA");
            assertThat(store.attempts("ANDINA:TRX-CO-5521907:APROBADA")).containsExactly("ntf-8f1c2a77", "ntf-c02d9e13");
        }

        @Test
        @DisplayName("Quetzal redelivery of the same event (same id and body) is published only once")
        void quetzalRedeliveryIsPublishedOnce() {
            IntakeService service = service(broker, store);

            assertThat(deliver(service, "quetzal", QUETZAL_SUCCEEDED)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(deliver(service, "quetzal", QUETZAL_SUCCEEDED)).isEqualTo(IntakeOutcome.DUPLICATE);
            assertThat(broker.published()).hasSize(1);
        }

        @Test
        @DisplayName("a different state of the same transaction is NOT a duplicate")
        void otherStateIsANewBusinessEvent() {
            IntakeService service = service(broker, store);

            deliver(service, "andina", ANDINA_APPROVED);
            assertThat(deliver(service, "andina", ANDINA_REVERSED)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(broker.published()).extracting(OutboundMessage::messageId)
                    .containsExactly("ANDINA:TRX-CO-5521907:APROBADA", "ANDINA:TRX-CO-5521907:REVERSADA");
        }

        @Test
        @DisplayName("out-of-order arrival (REVERSADA before APROBADA) publishes both; precedence is applied downstream")
        void outOfOrderEventsAreBothPublished() {
            IntakeService service = service(broker, store);

            assertThat(deliver(service, "andina", ANDINA_REVERSED)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(deliver(service, "andina", ANDINA_APPROVED)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(broker.published()).extracting(m -> m.applicationProperties().get("status"))
                    .containsExactly("REFUNDED", "CAPTURED");
        }

        @Test
        @DisplayName("idempotency store down -> fail-open: still published and accepted (never lose a payment)")
        void idempotencyStoreOutageFailsOpen() {
            IdempotencyStore broken = new IdempotencyStore() {
                public boolean isAccepted(String key) { throw new IllegalStateException("SQL unavailable"); }
                public void markAccepted(String key, String id) { throw new IllegalStateException("SQL unavailable"); }
                public void recordDuplicateAttempt(String key, String id) { throw new IllegalStateException("SQL unavailable"); }
            };
            IntakeService service = service(broker, broken);

            assertThat(deliver(service, "andina", ANDINA_APPROVED)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(deliver(service, "andina", ANDINA_APPROVED_RETRY)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(broker.published()).hasSize(2);
            assertThat(broker.published()).extracting(OutboundMessage::messageId).containsOnly("ANDINA:TRX-CO-5521907:APROBADA");
        }
    }

    @Nested
    @DisplayName("NFR3 / NFR4 - durability and provider timeouts")
    class Durability {

        @Test
        @DisplayName("broker failure -> 503 (not accepted) and the key is NOT marked, so the provider retry is published")
        void brokerFailureIsNotAcceptedAndRetryWorks() {
            AtomicBoolean brokerUp = new AtomicBoolean(false);
            EventPublisher flaky = message -> {
                if (!brokerUp.get()) {
                    throw new PublishException("Service Bus unavailable");
                }
                broker.publish(message);
            };
            IntakeService service = service(flaky, store);

            assertThat(deliver(service, "andina", ANDINA_APPROVED)).isEqualTo(IntakeOutcome.UNAVAILABLE);
            assertThat(store.isAccepted("ANDINA:TRX-CO-5521907:APROBADA")).isFalse();

            brokerUp.set(true);
            assertThat(deliver(service, "andina", ANDINA_APPROVED_RETRY)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(broker.published()).hasSize(1);
        }

        @Test
        @DisplayName("slow broker -> 503 within the publish budget, well before Andina's 5 s timeout")
        void slowBrokerAnswersWithinBudget() throws InterruptedException {
            CountDownLatch release = new CountDownLatch(1);
            EventPublisher hanging = message -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PublishException("interrupted");
                }
            };
            IntakeService service = service(hanging, store);

            long start = System.nanoTime();
            IntakeOutcome outcome = deliver(service, "andina", ANDINA_APPROVED);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            release.countDown();

            assertThat(outcome).isEqualTo(IntakeOutcome.UNAVAILABLE);
            assertThat(elapsedMs).isLessThan(2_000);
            assertThat(store.isAccepted("ANDINA:TRX-CO-5521907:APROBADA")).isFalse();
        }

        @Test
        @DisplayName("2xx is returned only after the publish returned")
        void acceptedOnlyAfterPublish() {
            AtomicInteger publishes = new AtomicInteger();
            EventPublisher counting = message -> publishes.incrementAndGet();
            IntakeService service = service(counting, store);

            IntakeOutcome outcome = deliver(service, "quetzal", QUETZAL_SUCCEEDED);
            assertThat(outcome.accepted()).isTrue();
            assertThat(publishes).hasValue(1);
        }

        @Test
        @DisplayName("marking the key after a successful publish may fail: still accepted (message is durable)")
        void markFailureAfterPublishIsStillAccepted() {
            IdempotencyStore markFails = new InMemoryIdempotencyStore() {
                @Override
                public void markAccepted(String key, String id) {
                    throw new IllegalStateException("SQL timeout");
                }
            };
            assertThat(deliver(service(broker, markFails), "quetzal", QUETZAL_SUCCEEDED)).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(broker.published()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("FR1 / error cases")
    class Errors {

        @Test
        @DisplayName("invalid signature -> 401 and nothing published, nothing marked")
        void invalidSignatureIsRejected() {
            IntakeService service = service(broker, store);
            Sample forged = new Sample("forged", ANDINA_REVERSED.body(), ANDINA_APPROVED.headers(), ANDINA_APPROVED.signedAt());

            assertThat(deliver(service, "andina", forged)).isEqualTo(IntakeOutcome.INVALID_SIGNATURE);
            assertThat(broker.published()).isEmpty();
        }

        @Test
        @DisplayName("the signature of one provider is not accepted on another provider's endpoint")
        void wrongEndpointIsRejected() {
            assertThat(deliver(service(broker, store), "andina", QUETZAL_SUCCEEDED)).isEqualTo(IntakeOutcome.INVALID_SIGNATURE);
        }

        @Test
        void unknownProviderPathIsNotFound() {
            assertThat(service(broker, store).handle("paypal", new WebhookRequest(new byte[0], Map.of()), "c"))
                    .isEqualTo(IntakeOutcome.UNKNOWN_PROVIDER);
        }

        @Test
        @DisplayName("Austral (Chile) is designed but not part of Part B: no adapter")
        void australIsNotImplementedHere() {
            assertThat(service(broker, store).handle("austral", new WebhookRequest(new byte[0], Map.of()), "c"))
                    .isEqualTo(IntakeOutcome.UNKNOWN_PROVIDER);
        }

        @Test
        void oversizedBodyIsRejectedBeforeAnyProcessing() {
            byte[] big = new byte[65_537];
            assertThat(service(broker, store).handle("quetzal", new WebhookRequest(big, Map.of()), "c"))
                    .isEqualTo(IntakeOutcome.PAYLOAD_TOO_LARGE);
        }
    }
}

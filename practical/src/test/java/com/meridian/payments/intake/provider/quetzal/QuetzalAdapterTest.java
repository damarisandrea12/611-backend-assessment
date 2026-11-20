package com.meridian.payments.intake.provider.quetzal;

import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_FAILED;
import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_REFUNDED;
import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meridian.payments.intake.canonical.CanonicalPaymentEvent;
import com.meridian.payments.intake.canonical.Country;
import com.meridian.payments.intake.canonical.PaymentMethod;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.provider.MappingContext;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.PayloadMappingException;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.ProviderSamples;
import com.meridian.payments.intake.support.ProviderSamples.Sample;

@DisplayName("Quetzal -> canonical event mapping (FR2)")
class QuetzalAdapterTest {

    private static final MappingContext CONTEXT = new MappingContext("corr-1", Instant.parse("2026-11-28T17:00:00Z"));

    private final QuetzalAdapter adapter = new QuetzalAdapter(
            new QuetzalSignatureVerifier(ProviderSamples.testSecrets(), new MutableClock(Instant.EPOCH)));

    private CanonicalPaymentEvent map(Sample sample) {
        return ((MappingResult.Mapped) adapter.map(sample.request(), CONTEXT)).event();
    }

    @Test
    void mapsChargeSucceededToCaptured() {
        CanonicalPaymentEvent event = map(QUETZAL_SUCCEEDED);

        assertThat(event.idempotencyKey()).isEqualTo("QUETZAL:evt_01JDQ3K8M2X7");
        assertThat(event.eventId()).isEqualTo(CanonicalPaymentEvent.eventIdFor("QUETZAL:evt_01JDQ3K8M2X7"));
        assertThat(event.provider()).isEqualTo(Provider.QUETZAL);
        assertThat(event.country()).isEqualTo(Country.MX);
        assertThat(event.providerEventId()).isEqualTo("evt_01JDQ3K8M2X7");
        assertThat(event.providerTransactionId()).isEqualTo("ch_7Hk2Lm9QpZ");
        assertThat(event.paymentKey()).isEqualTo("QUETZAL:ch_7Hk2Lm9QpZ");
        assertThat(event.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(event.providerStatus()).isEqualTo("charge.succeeded");
        assertThat(event.policyNumber()).isEqualTo("POL-MX-000412785");
        assertThat(event.paymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.receivedAt()).isEqualTo(CONTEXT.receivedAt());
    }

    @Test
    @DisplayName("amount: 125000 centavos (minor unit) -> \"1250.00\" MXN; currency upper-cased")
    void convertsMinorUnitsAndCurrency() {
        CanonicalPaymentEvent event = map(QUETZAL_SUCCEEDED);
        assertThat(event.amount()).isEqualTo("1250.00");
        assertThat(event.currency()).isEqualTo("MXN");
    }

    @Test
    @DisplayName("statusChangedAt of a capture is paid_at, not the event time")
    void usesPaidAtForCaptures() {
        assertThat(map(QUETZAL_SUCCEEDED).statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795888796L));
    }

    @Test
    void mapsChargeFailedWithReasonAndEventTime() {
        CanonicalPaymentEvent event = map(QUETZAL_FAILED);
        assertThat(event.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(event.statusReason()).isEqualTo("insufficient_funds");
        assertThat(event.amount()).isEqualTo("980.00");
        assertThat(event.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795889400L));
    }

    @Test
    @DisplayName("refund of the same charge targets the same payment key (one Salesforce record)")
    void mapsRefundToSamePaymentKey() {
        CanonicalPaymentEvent captured = map(QUETZAL_SUCCEEDED);
        CanonicalPaymentEvent refunded = map(QUETZAL_REFUNDED);

        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.paymentKey()).isEqualTo(captured.paymentKey());
        assertThat(refunded.idempotencyKey()).isNotEqualTo(captured.idempotencyKey());
        assertThat(refunded.refundedAmount()).isEqualTo("1250.00");
        assertThat(refunded.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795975195L));
        assertThat(refunded.status().rank()).isGreaterThan(captured.status().rank());
    }

    @Test
    @DisplayName("a retried delivery (same id, same body) produces the same idempotency key and event id")
    void retryProducesSameIdentity() {
        CanonicalPaymentEvent first = map(QUETZAL_SUCCEEDED);
        CanonicalPaymentEvent retry = map(QUETZAL_SUCCEEDED);

        assertThat(retry.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(retry.eventId()).isEqualTo(first.eventId());
    }

    @Test
    @DisplayName("unknown event types are acknowledged and ignored, never mapped to a default status")
    void ignoresUnknownEventTypes() {
        String body = "{\"id\":\"evt_X\",\"type\":\"charge.dispute.created\",\"created\":1795888800,\"data\":{\"charge\":{}}}";
        MappingResult result = adapter.map(new WebhookRequest(body.getBytes(StandardCharsets.UTF_8), Map.of()), CONTEXT);
        assertThat(result).isInstanceOf(MappingResult.Ignored.class);
    }

    @Test
    void rejectsMissingRequiredFields() {
        String body = "{\"id\":\"evt_X\",\"type\":\"charge.succeeded\",\"created\":1795888800,\"data\":{\"charge\":{\"id\":\"ch_1\"}}}";
        assertThatThrownBy(() -> adapter.map(new WebhookRequest(body.getBytes(StandardCharsets.UTF_8), Map.of()), CONTEXT))
                .isInstanceOf(PayloadMappingException.class);
    }

    @Test
    void rejectsInvalidJsonWithoutEchoingIt() {
        assertThatThrownBy(() -> adapter.map(new WebhookRequest("{not json maria@example.com".getBytes(StandardCharsets.UTF_8), Map.of()), CONTEXT))
                .isInstanceOf(PayloadMappingException.class)
                .hasMessage("body is not valid JSON");
    }

    @Test
    @DisplayName("customer email and payment token never reach the canonical event")
    void dropsPersonalData() {
        String json = map(QUETZAL_SUCCEEDED).toString();
        assertThat(json).doesNotContain("maria.lopez@example.com").doesNotContain("tok_qz_9sD2fK");
    }
}

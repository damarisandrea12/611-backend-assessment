package com.meridian.payments.intake.provider.andina;

import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED;
import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED_RETRY;
import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_PENDING;
import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_REVERSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.meridian.payments.intake.canonical.CanonicalPaymentEvent;
import com.meridian.payments.intake.canonical.Country;
import com.meridian.payments.intake.canonical.PaymentMethod;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.provider.MappingContext;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.PayloadMappingException;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.ProviderSamples;
import com.meridian.payments.intake.support.ProviderSamples.Sample;

@DisplayName("Andina -> canonical event mapping (FR2, FR4 keys, INC-0412 reference normalization)")
class AndinaAdapterTest {

    private static final MappingContext CONTEXT = new MappingContext("corr-1", Instant.parse("2026-11-28T19:03:12Z"));

    private final AndinaAdapter adapter = new AndinaAdapter(
            new AndinaSignatureVerifier(ProviderSamples.testSecrets(), new MutableClock(Instant.EPOCH)));

    private CanonicalPaymentEvent map(Sample sample) {
        return map(sample.body());
    }

    private CanonicalPaymentEvent map(byte[] body) {
        return ((MappingResult.Mapped) adapter.map(new WebhookRequest(body, Map.of()), CONTEXT)).event();
    }

    @Test
    void mapsApprovedToCaptured() {
        CanonicalPaymentEvent event = map(ANDINA_APPROVED);

        assertThat(event.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(event.providerStatus()).isEqualTo("APROBADA");
        assertThat(event.country()).isEqualTo(Country.CO);
        assertThat(event.idempotencyKey()).isEqualTo("ANDINA:TRX-CO-5521907:APROBADA");
        assertThat(event.paymentKey()).isEqualTo("ANDINA:TRX-CO-5521907");
        assertThat(event.providerEventId()).isEqualTo("ntf-8f1c2a77");
        assertThat(event.amount()).isEqualTo("185000.00");
        assertThat(event.currency()).isEqualTo("COP");
        assertThat(event.policyNumber()).isEqualTo("POL-CO-000187342");
        assertThat(event.paymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(event.statusChangedAt()).isEqualTo(Instant.parse("2026-11-28T19:03:09Z"));
    }

    @Test
    @DisplayName("FR4: a retry has a NEW notificacion_id but the SAME idempotency key and event id")
    void retryWithNewNotificationIdHasSameIdentity() {
        CanonicalPaymentEvent original = map(ANDINA_APPROVED);
        CanonicalPaymentEvent retry = map(ANDINA_APPROVED_RETRY);

        assertThat(retry.providerEventId()).isEqualTo("ntf-c02d9e13").isNotEqualTo(original.providerEventId());
        assertThat(retry.idempotencyKey()).isEqualTo(original.idempotencyKey());
        assertThat(retry.eventId()).isEqualTo(original.eventId());
        assertThat(retry.statusChangedAt()).isEqualTo(original.statusChangedAt());
    }

    @Test
    @DisplayName("each state of the same transaction is a distinct business event on the same payment")
    void lifecycleStatesShareThePaymentKey() {
        CanonicalPaymentEvent pending = map(ANDINA_PENDING);
        CanonicalPaymentEvent approved = map(ANDINA_APPROVED);
        CanonicalPaymentEvent reversed = map(ANDINA_REVERSED);

        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reversed.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reversed.statusReason()).isEqualTo("SOLICITUD_BANCO");
        assertThat(reversed.refundedAmount()).isEqualTo("185000.00");
        assertThat(pending.paymentKey()).isEqualTo(approved.paymentKey()).isEqualTo(reversed.paymentKey());
        assertThat(pending.idempotencyKey()).isNotEqualTo(approved.idempotencyKey());
        assertThat(pending.status().rank()).isLessThan(approved.status().rank());
        assertThat(approved.status().rank()).isLessThan(reversed.status().rank());
    }

    @ParameterizedTest(name = "reference \"{0}\" -> {1}")
    @CsvSource(nullValues = "null", value = {
            "POL-CO-000187342, POL-CO-000187342",
            "000215560, POL-CO-000215560",
            "pol-co-000198004, POL-CO-000198004",
            "' POL-CO-000187342 ', POL-CO-000187342",
            "POL-MX-000412785, null",
            "POL-CO-12345, null",
            "mi poliza, null"})
    @DisplayName("API 2.3 free-text reference is normalized, or left unresolved (null) - never rejected")
    void normalizesFreeTextReference(String reference, String expected) {
        String body = new String(ANDINA_APPROVED.body(), StandardCharsets.UTF_8)
                .replace("\"referencia\": \"POL-CO-000187342\"", "\"referencia\": \"" + reference + "\"");
        CanonicalPaymentEvent event = map(body.getBytes(StandardCharsets.UTF_8));
        assertThat(event.policyNumber()).isEqualTo(expected);
        assertThat(event.rawPolicyReference()).isEqualTo(reference);
    }

    @Test
    @DisplayName("unknown fields (API 2.3 transaccion.canal) are ignored")
    void ignoresUnknownFields() {
        String body = new String(ANDINA_APPROVED.body(), StandardCharsets.UTF_8)
                .replace("\"medio_pago\": \"PSE\",", "\"medio_pago\": \"PSE\", \"canal\": \"APP\", \"nuevo\": {\"x\": 1},");
        assertThat(map(body.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("an unknown estado is rejected, never mapped to a default status")
    void rejectsUnknownState() {
        String body = new String(ANDINA_APPROVED.body(), StandardCharsets.UTF_8).replace("APROBADA", "EN_REVISION");
        assertThatThrownBy(() -> map(body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PayloadMappingException.class)
                .hasMessageNotContaining("EN_REVISION");
    }

    @ParameterizedTest(name = "amount \"{0}\"")
    @CsvSource({"185000.001", "-1", "1e5", "abc"})
    void rejectsInvalidAmounts(String amount) {
        String body = new String(ANDINA_APPROVED.body(), StandardCharsets.UTF_8).replace("185000.00", amount);
        assertThatThrownBy(() -> map(body.getBytes(StandardCharsets.UTF_8))).isInstanceOf(PayloadMappingException.class);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        String body = new String(ANDINA_APPROVED.body(), StandardCharsets.UTF_8).replace("\"COP\"", "\"USD\"");
        assertThatThrownBy(() -> map(body.getBytes(StandardCharsets.UTF_8))).isInstanceOf(PayloadMappingException.class);
    }

    @Test
    @DisplayName("payer name, national id and email never reach the canonical event")
    void dropsPersonalData() {
        String event = map(ANDINA_APPROVED).toString();
        for (String personal : ProviderSamples.PERSONAL_DATA) {
            assertThat(event).doesNotContain(personal);
        }
    }
}
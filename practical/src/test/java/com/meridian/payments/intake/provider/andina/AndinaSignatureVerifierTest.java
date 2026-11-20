package com.meridian.payments.intake.provider.andina;

import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED;
import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED_RETRY;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.meridian.payments.intake.provider.SignatureVerificationException;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.ProviderSamples;
import com.meridian.payments.intake.support.ProviderSamples.Sample;

@DisplayName("Andina signature verification (FR1) with the reference test vectors")
class AndinaSignatureVerifierTest {

    private final MutableClock clock = new MutableClock(ANDINA_APPROVED.signedAt());
    private final AndinaSignatureVerifier verifier = new AndinaSignatureVerifier(ProviderSamples.testSecrets(), clock);

    static Stream<Sample> vectors() {
        return ProviderSamples.ANDINA_ALL.stream();
    }

    private void verify(Sample sample) {
        verifier.verify(sample.headers().get(AndinaSignatureVerifier.TIMESTAMP_HEADER),
                sample.headers().get(AndinaSignatureVerifier.SIGNATURE_HEADER), sample.body());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void acceptsEveryReferenceVector(Sample sample) {
        clock.set(sample.signedAt().plusSeconds(1));
        assertThatCode(() -> verify(sample)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTamperedBody() {
        String tampered = new String(ANDINA_APPROVED.body(), StandardCharsets.UTF_8).replace("APROBADA", "REVERSADA");
        assertThatThrownBy(() -> verifier.verify(ANDINA_APPROVED.headers().get(AndinaSignatureVerifier.TIMESTAMP_HEADER),
                ANDINA_APPROVED.headers().get(AndinaSignatureVerifier.SIGNATURE_HEADER),
                tampered.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessage("signature mismatch");
    }

    @Test
    @DisplayName("the timestamp header is signed: a different timestamp with the same signature is rejected")
    void rejectsTimestampNotMatchingSignature() {
        clock.set(ANDINA_APPROVED_RETRY.signedAt());
        assertThatThrownBy(() -> verifier.verify(ANDINA_APPROVED_RETRY.headers().get(AndinaSignatureVerifier.TIMESTAMP_HEADER),
                ANDINA_APPROVED.headers().get(AndinaSignatureVerifier.SIGNATURE_HEADER), ANDINA_APPROVED.body()))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    @DisplayName("replay protection: rejected when the timestamp is more than 10 minutes away")
    void rejectsStaleTimestamp() {
        clock.set(ANDINA_APPROVED.signedAt().plus(Duration.ofMinutes(10)).plusSeconds(1));
        assertThatThrownBy(() -> verify(ANDINA_APPROVED))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessage("timestamp outside tolerance");
    }

    @Test
    void rejectsTimestampTooFarInTheFuture() {
        clock.set(ANDINA_APPROVED.signedAt().minus(Duration.ofMinutes(11)));
        assertThatThrownBy(() -> verify(ANDINA_APPROVED)).isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void acceptsTimestampExactlyTenMinutesOld() {
        clock.set(ANDINA_APPROVED.signedAt().plus(Duration.ofMinutes(10)));
        assertThatCode(() -> verify(ANDINA_APPROVED)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"not-base64-!!!", "AAAA", ""})
    void rejectsMalformedOrWrongSignature(String signature) {
        assertThatThrownBy(() -> verifier.verify(ANDINA_APPROVED.headers().get(AndinaSignatureVerifier.TIMESTAMP_HEADER),
                signature, ANDINA_APPROVED.body()))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"yesterday", "2026-11-28 14:03:11", "1795888803"})
    void rejectsMalformedTimestamp(String timestamp) {
        assertThatThrownBy(() -> verifier.verify(timestamp,
                ANDINA_APPROVED.headers().get(AndinaSignatureVerifier.SIGNATURE_HEADER), ANDINA_APPROVED.body()))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void rejectsMissingHeaders() {
        assertThatThrownBy(() -> verifier.verify(null, "x", ANDINA_APPROVED.body()))
                .isInstanceOf(SignatureVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(ANDINA_APPROVED.headers().get(AndinaSignatureVerifier.TIMESTAMP_HEADER),
                null, ANDINA_APPROVED.body()))
                .isInstanceOf(SignatureVerificationException.class);
    }
}

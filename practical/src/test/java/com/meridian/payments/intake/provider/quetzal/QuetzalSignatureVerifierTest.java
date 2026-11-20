package com.meridian.payments.intake.provider.quetzal;

import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_FAILED;
import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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

@DisplayName("Quetzal signature verification (FR1) with the reference test vectors")
class QuetzalSignatureVerifierTest {

    private final MutableClock clock = new MutableClock(QUETZAL_SUCCEEDED.signedAt());
    private final QuetzalSignatureVerifier verifier = new QuetzalSignatureVerifier(ProviderSamples.testSecrets(), clock);

    static Stream<Sample> vectors() {
        return ProviderSamples.QUETZAL_ALL.stream();
    }

    private void verify(Sample sample) {
        verifier.verify(sample.headers().get(QuetzalSignatureVerifier.HEADER), sample.body());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void acceptsEveryReferenceVector(Sample sample) {
        clock.set(sample.signedAt().plusSeconds(2));
        assertThatCode(() -> verify(sample)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBodyModifiedByOneByte() {
        byte[] tampered = QUETZAL_SUCCEEDED.body().clone();
        String text = new String(tampered, StandardCharsets.UTF_8).replace("125000", "125001");
        assertThatThrownBy(() -> verifier.verify(QUETZAL_SUCCEEDED.headers().get(QuetzalSignatureVerifier.HEADER),
                text.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessage("signature mismatch");
    }

    @Test
    @DisplayName("signature is computed over the raw bytes: re-serialized (minified) JSON does not verify")
    void rejectsReserializedJson() {
        String minified = new String(QUETZAL_SUCCEEDED.body(), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        assertThatThrownBy(() -> verifier.verify(QUETZAL_SUCCEEDED.headers().get(QuetzalSignatureVerifier.HEADER),
                minified.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void rejectsSignatureOfAnotherEvent() {
        String otherSignature = QUETZAL_FAILED.headers().get(QuetzalSignatureVerifier.HEADER);
        clock.set(QUETZAL_FAILED.signedAt());
        assertThatThrownBy(() -> verifier.verify(otherSignature, QUETZAL_SUCCEEDED.body()))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void rejectsWrongSecret() {
        QuetzalSignatureVerifier wrongSecret = new QuetzalSignatureVerifier(name -> List.of("not_the_secret"), clock);
        assertThatThrownBy(() -> wrongSecret.verify(QUETZAL_SUCCEEDED.headers().get(QuetzalSignatureVerifier.HEADER),
                QUETZAL_SUCCEEDED.body()))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    @DisplayName("fails closed when no secret is configured")
    void rejectsWhenNoSecretConfigured() {
        QuetzalSignatureVerifier noSecret = new QuetzalSignatureVerifier(name -> List.of(), clock);
        assertThatThrownBy(() -> noSecret.verify(QUETZAL_SUCCEEDED.headers().get(QuetzalSignatureVerifier.HEADER),
                QUETZAL_SUCCEEDED.body()))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessage("no webhook secret configured");
    }

    @ParameterizedTest(name = "now = t {0} s")
    @ValueSource(longs = {301, -301, 86_400})
    void rejectsTimestampOutside300Seconds(long offsetSeconds) {
        clock.set(QUETZAL_SUCCEEDED.signedAt().plusSeconds(offsetSeconds));
        assertThatThrownBy(() -> verify(QUETZAL_SUCCEEDED))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessage("timestamp outside tolerance");
    }

    @ParameterizedTest(name = "now = t {0} s")
    @ValueSource(longs = {300, -300, 0})
    void acceptsTimestampAtTheToleranceLimit(long offsetSeconds) {
        clock.set(QUETZAL_SUCCEEDED.signedAt().plusSeconds(offsetSeconds));
        assertThatCode(() -> verify(QUETZAL_SUCCEEDED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("secret rotation: valid if any of several v1 signatures matches")
    void acceptsWhenAnyOfSeveralSignaturesMatches() {
        String header = "t=1795888803,v1=" + "0".repeat(64)
                + ",v1=2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031";
        assertThatCode(() -> verifier.verify(header, QUETZAL_SUCCEEDED.body())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the timestamp is part of the signed content: changing t invalidates the signature")
    void rejectsChangedTimestamp() {
        String header = QUETZAL_SUCCEEDED.headers().get(QuetzalSignatureVerifier.HEADER).replace("t=1795888803", "t=1795888804");
        assertThatThrownBy(() -> verifier.verify(header, QUETZAL_SUCCEEDED.body()))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessage("signature mismatch");
    }

    @Test
    void acceptsSecondActiveSecretDuringMeridianSideRotation() {
        QuetzalSignatureVerifier rotating = new QuetzalSignatureVerifier(
                name -> Arrays.asList("new_secret_not_yet_used_by_provider", ProviderSamples.QUETZAL_TEST_SECRET), clock);
        assertThatCode(() -> rotating.verify(QUETZAL_SUCCEEDED.headers().get(QuetzalSignatureVerifier.HEADER),
                QUETZAL_SUCCEEDED.body())).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "garbage", "t=1795888803", "v1=abc", "t=abc,v1=00", "t=1,t=2,v1=00", "=x"})
    void rejectsMalformedHeaders(String header) {
        assertThatThrownBy(() -> verifier.verify(header, QUETZAL_SUCCEEDED.body()))
                .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    void rejectsMissingHeader() {
        assertThatThrownBy(() -> verifier.verify(null, QUETZAL_SUCCEEDED.body()))
                .isInstanceOf(SignatureVerificationException.class)
                .hasMessageContaining("missing");
    }
}

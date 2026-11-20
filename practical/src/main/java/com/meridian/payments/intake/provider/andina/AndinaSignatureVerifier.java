package com.meridian.payments.intake.provider.andina;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

import com.meridian.payments.intake.crypto.HmacSha256;
import com.meridian.payments.intake.provider.SignatureVerificationException;
import com.meridian.payments.intake.secrets.SecretProvider;

public class AndinaSignatureVerifier {

    public static final String TIMESTAMP_HEADER = "X-Andina-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Andina-Signature";
    public static final String SECRET_NAME = "andina-webhook";
    static final Duration TOLERANCE = Duration.ofMinutes(10);

    private final SecretProvider secretProvider;
    private final Clock clock;

    public AndinaSignatureVerifier(SecretProvider secretProvider, Clock clock) {
        this.secretProvider = secretProvider;
        this.clock = clock;
    }

    public void verify(String timestampHeader, String signatureHeader, byte[] rawBody) {
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new SignatureVerificationException("missing " + TIMESTAMP_HEADER + " header");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new SignatureVerificationException("missing " + SIGNATURE_HEADER + " header");
        }

        Instant timestamp;
        try {
            timestamp = OffsetDateTime.parse(timestampHeader).toInstant();
        } catch (DateTimeParseException e) {
            throw new SignatureVerificationException("malformed " + TIMESTAMP_HEADER + " header");
        }
        Duration skew = Duration.between(timestamp, clock.instant()).abs();
        if (skew.compareTo(TOLERANCE) > 0) {
            throw new SignatureVerificationException("timestamp outside tolerance");
        }

        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signatureHeader.strip());
        } catch (IllegalArgumentException e) {
            throw new SignatureVerificationException("malformed " + SIGNATURE_HEADER + " header");
        }

        List<String> secrets = secretProvider.secrets(SECRET_NAME);
        if (secrets.isEmpty()) {
            throw new SignatureVerificationException("no webhook secret configured");
        }
        String signedPrefix = timestampHeader + ".";
        for (String secret : secrets) {
            if (HmacSha256.constantTimeEquals(HmacSha256.sign(secret, signedPrefix, rawBody), provided)) {
                return;
            }
        }
        throw new SignatureVerificationException("signature mismatch");
    }
}

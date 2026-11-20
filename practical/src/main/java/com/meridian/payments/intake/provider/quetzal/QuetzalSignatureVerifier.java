package com.meridian.payments.intake.provider.quetzal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.meridian.payments.intake.crypto.HmacSha256;
import com.meridian.payments.intake.provider.SignatureVerificationException;
import com.meridian.payments.intake.secrets.SecretProvider;

public class QuetzalSignatureVerifier {

    public static final String HEADER = "Quetzal-Signature";
    public static final String SECRET_NAME = "quetzal-webhook";
    static final Duration TOLERANCE = Duration.ofSeconds(300);

    private final SecretProvider secretProvider;
    private final Clock clock;

    public QuetzalSignatureVerifier(SecretProvider secretProvider, Clock clock) {
        this.secretProvider = secretProvider;
        this.clock = clock;
    }

    public void verify(String headerValue, byte[] rawBody) {
        ParsedHeader header = parseHeader(headerValue);

        long signedAt = Long.parseLong(header.timestamp());
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - signedAt) > TOLERANCE.toSeconds()) {
            throw new SignatureVerificationException("timestamp outside tolerance");
        }

        List<String> secrets = secretProvider.secrets(SECRET_NAME);
        if (secrets.isEmpty()) {
            throw new SignatureVerificationException("no webhook secret configured");
        }
        String signedPrefix = header.timestamp() + ".";
        for (String secret : secrets) {
            byte[] expected = HexFormat.of().formatHex(HmacSha256.sign(secret, signedPrefix, rawBody))
                    .getBytes(StandardCharsets.US_ASCII);
            for (String candidate : header.signatures()) {
                if (HmacSha256.constantTimeEquals(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                    return;
                }
            }
        }
        throw new SignatureVerificationException("signature mismatch");
    }

    private static ParsedHeader parseHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new SignatureVerificationException("missing " + HEADER + " header");
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : headerValue.split(",")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                throw new SignatureVerificationException("malformed " + HEADER + " header");
            }
            String key = part.substring(0, separator).strip();
            String value = part.substring(separator + 1).strip();
            switch (key) {
                case "t" -> {
                    if (timestamp != null) {
                        throw new SignatureVerificationException("duplicate timestamp in " + HEADER);
                    }
                    timestamp = value;
                }
                case "v1" -> signatures.add(value);
                default -> { /* unknown schemes (e.g. future v2) are ignored */ }
            }
        }
        if (timestamp == null || !timestamp.matches("^\\d{1,12}$") || signatures.isEmpty()) {
            throw new SignatureVerificationException("malformed " + HEADER + " header");
        }
        return new ParsedHeader(timestamp, signatures);
    }

    private record ParsedHeader(String timestamp, List<String> signatures) {
    }
}
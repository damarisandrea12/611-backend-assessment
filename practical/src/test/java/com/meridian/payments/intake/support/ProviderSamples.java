package com.meridian.payments.intake.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.provider.andina.AndinaSignatureVerifier;
import com.meridian.payments.intake.provider.quetzal.QuetzalSignatureVerifier;
import com.meridian.payments.intake.secrets.SecretProvider;

public final class ProviderSamples {

    public static final String QUETZAL_TEST_SECRET = "qz_test_whsec_4f8a1c2e9b7d";
    public static final String ANDINA_TEST_SECRET = "andina_test_secret_7c3e19ab";

    private static final Path REFERENCE = Path.of("..", "reference", "providers");

    public record Sample(String file, byte[] body, Map<String, String> headers, Instant signedAt) {
        public WebhookRequest request() {
            return new WebhookRequest(body, headers);
        }
    }

    public static final Sample QUETZAL_SUCCEEDED = quetzal("01-charge-succeeded.json", "1795888803",
            "2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031");
    public static final Sample QUETZAL_FAILED = quetzal("02-charge-failed.json", "1795889403",
            "d793d3da0a8d4d66e984ada3daa8b9f473a0eef153d99cdd83bb70f60f13ccc9");
    public static final Sample QUETZAL_REFUNDED = quetzal("03-charge-refunded.json", "1795975203",
            "a61f9d88d861cd4cc63359b5ca6e6d11d55369db7079387dee9a5024ccb2cae6");

    public static final Sample ANDINA_PENDING = andina("01-transaccion-pendiente.json",
            "2026-11-28T14:02:59-05:00", "JhaBZfk7eQsoMbbQbThmAx/bCyehBOzKYw8XTP2a40k=");
    public static final Sample ANDINA_APPROVED = andina("02-transaccion-aprobada.json",
            "2026-11-28T14:03:11-05:00", "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=");
    public static final Sample ANDINA_APPROVED_RETRY = andina("03-transaccion-aprobada-reintento.json",
            "2026-11-28T14:08:11-05:00", "CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424=");
    public static final Sample ANDINA_REVERSED = andina("04-transaccion-reversada.json",
            "2026-12-03T09:15:42-05:00", "AfGO+MEffk0AWIEEE3cgyff4hWewoOaDhygCWxs/fNI=");

    public static final List<Sample> QUETZAL_ALL = List.of(QUETZAL_SUCCEEDED, QUETZAL_FAILED, QUETZAL_REFUNDED);
    public static final List<Sample> ANDINA_ALL = List.of(ANDINA_PENDING, ANDINA_APPROVED, ANDINA_APPROVED_RETRY, ANDINA_REVERSED);

    public static final List<String> PERSONAL_DATA = List.of(
            "maria.lopez@example.com", "jorge.ramirez@example.com",
            "Laura", "Gómez", "1020304050", "laura.gomez@example.com",
            "tok_qz_9sD2fK", "tok_qz_1aB7cD");

    private ProviderSamples() {
    }

    public static SecretProvider testSecrets() {
        Map<String, List<String>> secrets = Map.of(
                QuetzalSignatureVerifier.SECRET_NAME, List.of(QUETZAL_TEST_SECRET),
                AndinaSignatureVerifier.SECRET_NAME, List.of(ANDINA_TEST_SECRET));
        return name -> secrets.getOrDefault(name, List.of());
    }

    public static byte[] read(String provider, String file) {
        try {
            return Files.readAllBytes(REFERENCE.resolve(provider).resolve("samples").resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Run the tests from the practical/ directory", e);
        }
    }

    private static Sample quetzal(String file, String t, String v1) {
        return new Sample(file, read("quetzal-mx", file),
                Map.of(QuetzalSignatureVerifier.HEADER, "t=" + t + ",v1=" + v1),
                Instant.ofEpochSecond(Long.parseLong(t)));
    }

    private static Sample andina(String file, String timestamp, String signature) {
        return new Sample(file, read("andina-co", file),
                Map.of(AndinaSignatureVerifier.TIMESTAMP_HEADER, timestamp,
                        AndinaSignatureVerifier.SIGNATURE_HEADER, signature),
                OffsetDateTime.parse(timestamp).toInstant());
    }
}

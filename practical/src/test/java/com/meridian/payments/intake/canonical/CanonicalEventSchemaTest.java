package com.meridian.payments.intake.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.core.IntakeService;
import com.meridian.payments.intake.provider.MappingContext;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.provider.andina.AndinaSignatureVerifier;
import com.meridian.payments.intake.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.provider.quetzal.QuetzalSignatureVerifier;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.ProviderSamples;
import com.meridian.payments.intake.support.ProviderSamples.Sample;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

@DisplayName("Every canonical event conforms to docs/api/events/premium-payment.schema.json (contract first)")
class CanonicalEventSchemaTest {

    private static final Path SCHEMA = Path.of("..", "docs", "api", "events", "premium-payment.schema.json");
    private static final ObjectMapper MAPPER = IntakeService.canonicalEventMapper();
    private static JsonSchema schema;

    private static final MutableClock CLOCK = new MutableClock(Instant.EPOCH);
    private static final ProviderAdapter QUETZAL = new QuetzalAdapter(new QuetzalSignatureVerifier(ProviderSamples.testSecrets(), CLOCK));
    private static final ProviderAdapter ANDINA = new AndinaAdapter(new AndinaSignatureVerifier(ProviderSamples.testSecrets(), CLOCK));

    @BeforeAll
    static void loadSchema() throws IOException {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        try (InputStream in = Files.newInputStream(SCHEMA)) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        }
    }

    record Case(String name, ProviderAdapter adapter, Sample sample) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> allSamples() {
        return Stream.concat(
                ProviderSamples.QUETZAL_ALL.stream().map(s -> new Case("quetzal/" + s.file(), QUETZAL, s)),
                ProviderSamples.ANDINA_ALL.stream().map(s -> new Case("andina/" + s.file(), ANDINA, s)));
    }

    private static JsonNode toJson(Case c) {
        MappingResult result = c.adapter().map(c.sample().request(),
                new MappingContext("7f3a0c2e-1d2b-4a6e-9a51-2b3c4d5e6f70", Instant.parse("2026-11-28T19:03:11.240Z")));
        return MAPPER.valueToTree(((MappingResult.Mapped) result).event());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSamples")
    void mappedSampleIsValidAgainstTheSchema(Case c) {
        Set<ValidationMessage> errors = schema.validate(toJson(c));
        assertThat(errors).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSamples")
    void eventContainsNoPersonalData(Case c) {
        String json = toJson(c).toString();
        for (String personal : ProviderSamples.PERSONAL_DATA) {
            assertThat(json).doesNotContain(personal);
        }
    }

    @Test
    @DisplayName("unresolved policy reference is still a valid event (policyNumber: null)")
    void unresolvedPolicyIsValid() {
        ObjectNode json = (ObjectNode) toJson(new Case("andina", ANDINA, ProviderSamples.ANDINA_APPROVED));
        json.putNull("policyNumber");
        assertThat(schema.validate(json)).isEmpty();
    }

    @Test
    @DisplayName("the schema itself is strict: a wrong status or a float amount is rejected")
    void schemaRejectsInvalidEvents() {
        ObjectNode badStatus = (ObjectNode) toJson(new Case("andina", ANDINA, ProviderSamples.ANDINA_APPROVED));
        badStatus.put("status", "APROBADA");
        ObjectNode floatAmount = (ObjectNode) toJson(new Case("andina", ANDINA, ProviderSamples.ANDINA_APPROVED));
        floatAmount.put("amount", 185000.0);
        ObjectNode missingKey = (ObjectNode) toJson(new Case("andina", ANDINA, ProviderSamples.ANDINA_APPROVED));
        missingKey.remove("idempotencyKey");

        for (JsonNode invalid : List.of(badStatus, floatAmount, missingKey)) {
            assertThat(schema.validate(invalid)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("forward compatibility: consumers' schema accepts unknown fields and any 1.x version")
    void schemaAcceptsAdditiveMinorChanges() {
        ObjectNode json = (ObjectNode) toJson(new Case("andina", ANDINA, ProviderSamples.ANDINA_APPROVED));
        json.put("schemaVersion", "1.3");
        json.put("someFutureOptionalField", "x");
        assertThat(schema.validate(json)).isEmpty();
    }
}

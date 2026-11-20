package com.meridian.payments.intake.web;

import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED;
import static com.meridian.payments.intake.support.ProviderSamples.ANDINA_APPROVED_RETRY;
import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_REFUNDED;
import static com.meridian.payments.intake.support.ProviderSamples.QUETZAL_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.idempotency.InMemoryIdempotencyStore;
import com.meridian.payments.intake.messaging.InMemoryEventPublisher;
import com.meridian.payments.intake.messaging.OutboundMessage;
import com.meridian.payments.intake.secrets.SecretProvider;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.ProviderSamples;
import com.meridian.payments.intake.support.ProviderSamples.Sample;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("POST /webhooks/{provider} end to end")
class WebhookIntakeIntegrationTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.EPOCH);
        }

        @Bean
        @Primary
        SecretProvider testSecretProvider() {
            return ProviderSamples.testSecrets();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    InMemoryEventPublisher broker;
    @Autowired
    InMemoryIdempotencyStore store;
    @Autowired
    Clock injectedClock;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        broker.clear();
        store.clear();
    }

    private MockHttpServletRequestBuilder webhook(String provider, Sample sample) {
        clock.set(sample.signedAt().plusSeconds(1));
        MockHttpServletRequestBuilder request = post("/webhooks/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sample.body());
        sample.headers().forEach(request::header);
        return request;
    }

    @Test
    void usesTheTestClock() {
        assertThat(injectedClock).isSameAs(clock);
    }

    @Test
    @DisplayName("valid Andina notification -> 200 ACCEPTED, correlation id echoed, event published with metadata")
    void acceptsAndinaNotification() throws Exception {
        mvc.perform(webhook("andina", ANDINA_APPROVED).header("X-Correlation-Id", "apim-1234"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "apim-1234"))
                .andExpect(jsonPath("$.result").value("ACCEPTED"));

        assertThat(broker.published()).hasSize(1);
        OutboundMessage message = broker.published().get(0);
        assertThat(message.messageId()).isEqualTo("ANDINA:TRX-CO-5521907:APROBADA");
        assertThat(message.correlationId()).isEqualTo("apim-1234");
        assertThat(message.applicationProperties())
                .containsEntry("schemaVersion", "1.0")
                .containsEntry("country", "CO")
                .containsEntry("provider", "ANDINA")
                .containsEntry("status", "CAPTURED");

        JsonNode event = mapper.readTree(message.body());
        assertThat(event.get("statusChangedAt").asText()).isEqualTo("2026-11-28T19:03:09Z");
        assertThat(event.get("amount").asText()).isEqualTo("185000.00");
        assertThat(event.get("correlationId").asText()).isEqualTo("apim-1234");
    }

    @Test
    @DisplayName("published bytes conform to the JSON Schema contract")
    void publishedEventMatchesSchema() throws Exception {
        mvc.perform(webhook("quetzal", QUETZAL_REFUNDED)).andExpect(status().isOk());

        JsonSchema schema;
        try (InputStream in = Files.newInputStream(Path.of("..", "docs", "api", "events", "premium-payment.schema.json"))) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
        assertThat(schema.validate(mapper.readTree(broker.published().get(0).body()))).isEmpty();
    }

    @Test
    @DisplayName("Andina retry -> 200 DUPLICATE and no second message")
    void retryIsAcknowledgedButNotRepublished() throws Exception {
        mvc.perform(webhook("andina", ANDINA_APPROVED)).andExpect(jsonPath("$.result").value("ACCEPTED"));
        mvc.perform(webhook("andina", ANDINA_APPROVED_RETRY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE"));
        assertThat(broker.published()).hasSize(1);
    }

    @Test
    @DisplayName("invalid signature -> 401 problem+json without details; nothing published")
    void rejectsInvalidSignature() throws Exception {
        byte[] tampered = new String(QUETZAL_SUCCEEDED.body(), StandardCharsets.UTF_8)
                .replace("charge.succeeded", "charge.refunded").getBytes(StandardCharsets.UTF_8);
        Sample forged = new Sample("forged", tampered, QUETZAL_SUCCEEDED.headers(), QUETZAL_SUCCEEDED.signedAt());

        mvc.perform(webhook("quetzal", forged))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("INVALID_SIGNATURE"))
                .andExpect(jsonPath("$.detail").doesNotExist());
        assertThat(broker.published()).isEmpty();
    }

    @Test
    @DisplayName("stale timestamp (replay of a captured request 10 minutes later) -> 401")
    void rejectsReplayedRequest() throws Exception {
        MockHttpServletRequestBuilder request = webhook("quetzal", QUETZAL_SUCCEEDED);
        clock.set(QUETZAL_SUCCEEDED.signedAt().plusSeconds(600));
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    @Test
    void missingSignatureHeaderIs401() throws Exception {
        clock.set(QUETZAL_SUCCEEDED.signedAt());
        mvc.perform(post("/webhooks/quetzal").contentType(MediaType.APPLICATION_JSON).content(QUETZAL_SUCCEEDED.body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownProviderIs404() throws Exception {
        mvc.perform(post("/webhooks/unknown").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        mvc.perform(post("/webhooks/quetzal").contentType(MediaType.APPLICATION_JSON).content(new byte[70_000]))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("an unsafe correlation id from the outside is replaced, not logged")
    void unsafeCorrelationIdIsReplaced() throws Exception {
        String injected = "abc\nFAKE LOG LINE";
        mvc.perform(webhook("andina", ANDINA_APPROVED).header("X-Correlation-Id", injected))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", not(injected)));
    }

    @Test
    @DisplayName("NFR6: payer personal data never appears in logs, even when processing fails")
    void personalDataIsNeverLogged(CapturedOutput output) throws Exception {
        mvc.perform(webhook("andina", ANDINA_APPROVED)).andExpect(status().isOk());
        mvc.perform(webhook("andina", ANDINA_APPROVED_RETRY)).andExpect(status().isOk());
        mvc.perform(webhook("quetzal", QUETZAL_SUCCEEDED)).andExpect(status().isOk());
        byte[] tampered = new String(ANDINA_APPROVED.body(), StandardCharsets.UTF_8).replace("Laura", "Laurita")
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(webhook("andina", new Sample("x", tampered, ANDINA_APPROVED.headers(), ANDINA_APPROVED.signedAt())))
                .andExpect(status().isUnauthorized());

        assertThat(output.getAll()).contains("Webhook accepted provider=ANDINA");
        for (String personal : ProviderSamples.PERSONAL_DATA) {
            assertThat(output.getAll()).doesNotContain(personal);
        }
        assertThat(output.getAll()).doesNotContain(ProviderSamples.ANDINA_TEST_SECRET).doesNotContain(ProviderSamples.QUETZAL_TEST_SECRET);
    }
}

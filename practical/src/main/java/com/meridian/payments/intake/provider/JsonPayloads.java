package com.meridian.payments.intake.provider;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPayloads() {
    }

    public static JsonNode parse(byte[] rawBody) {
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            if (node == null || !node.isObject()) {
                throw new PayloadMappingException("body is not a JSON object");
            }
            return node;
        } catch (IOException e) {
            // Do not attach the parser message: it can echo payload content.
            throw new PayloadMappingException("body is not valid JSON");
        }
    }

    public static String requiredText(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (node.isMissingNode() || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new PayloadMappingException("missing or invalid field " + pointer);
        }
        return node.asText();
    }

    public static String optionalText(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    public static long requiredLong(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (!node.isIntegralNumber()) {
            throw new PayloadMappingException("missing or invalid field " + pointer);
        }
        return node.asLong();
    }

    public static Long optionalLong(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new PayloadMappingException("invalid field " + pointer);
        }
        return node.asLong();
    }

    public static Instant requiredOffsetDateTime(JsonNode root, String pointer) {
        String value = requiredText(root, pointer);
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new PayloadMappingException("invalid date-time in " + pointer);
        }
    }

    public static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

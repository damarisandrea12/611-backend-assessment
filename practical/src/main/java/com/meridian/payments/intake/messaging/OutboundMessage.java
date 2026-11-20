package com.meridian.payments.intake.messaging;

import java.util.Map;

public record OutboundMessage(
        String messageId,
        String correlationId,
        byte[] body,
        Map<String, String> applicationProperties) {

    public static final String CONTENT_TYPE = "application/json";
}

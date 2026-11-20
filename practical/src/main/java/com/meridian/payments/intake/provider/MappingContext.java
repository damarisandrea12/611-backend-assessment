package com.meridian.payments.intake.provider;

import java.time.Instant;

public record MappingContext(String correlationId, Instant receivedAt) {
}

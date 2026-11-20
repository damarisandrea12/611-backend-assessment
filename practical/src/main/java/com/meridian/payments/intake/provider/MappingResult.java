package com.meridian.payments.intake.provider;

import com.meridian.payments.intake.canonical.CanonicalPaymentEvent;

public sealed interface MappingResult {

    record Mapped(CanonicalPaymentEvent event) implements MappingResult {
    }

    record Ignored(String providerEventId, String reason) implements MappingResult {
    }
}

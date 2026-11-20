package com.meridian.payments.intake.provider;

import com.meridian.payments.intake.canonical.Provider;

public interface ProviderAdapter {

    Provider provider();

    void verifySignature(WebhookRequest request) throws SignatureVerificationException;

    MappingResult map(WebhookRequest request, MappingContext context) throws PayloadMappingException;
}

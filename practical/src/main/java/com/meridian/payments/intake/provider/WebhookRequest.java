package com.meridian.payments.intake.provider;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class WebhookRequest {

    private final byte[] rawBody;
    private final Map<String, String> headers;

    public WebhookRequest(byte[] rawBody, Map<String, String> headers) {
        this.rawBody = rawBody == null ? new byte[0] : rawBody;
        this.headers = headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().toLowerCase(Locale.ROOT), e -> e.getValue(),
                        (first, second) -> first));
    }

    public byte[] rawBody() {
        return rawBody;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}

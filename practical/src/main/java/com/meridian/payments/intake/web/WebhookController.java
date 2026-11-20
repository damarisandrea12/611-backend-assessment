package com.meridian.payments.intake.web;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.intake.core.IntakeOutcome;
import com.meridian.payments.intake.core.IntakeService;
import com.meridian.payments.intake.provider.WebhookRequest;

@RestController
public class WebhookController {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    private final IntakeService intakeService;

    public WebhookController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping(path = "/webhooks/{provider}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable("provider") String provider,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        String correlationId = correlationId(headers.getFirst(CORRELATION_HEADER));
        MDC.put("correlationId", correlationId);
        try {
            WebhookRequest request = new WebhookRequest(body, firstValues(headers));
            IntakeOutcome outcome = intakeService.handle(provider, request, correlationId);
            return response(outcome, correlationId);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static ResponseEntity<Map<String, Object>> response(IntakeOutcome outcome, String correlationId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.httpStatus())
                .header(CORRELATION_HEADER, correlationId);
        if (outcome.accepted()) {
            return builder.contentType(MediaType.APPLICATION_JSON).body(ackBody(outcome, correlationId));
        }
        if (outcome == IntakeOutcome.UNAVAILABLE) {
            builder.header(HttpHeaders.RETRY_AFTER, "30");
        }
        return builder.contentType(PROBLEM_JSON).body(problemBody(outcome, correlationId));
    }

    private static Map<String, Object> ackBody(IntakeOutcome outcome, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", outcome.name());
        body.put("correlationId", correlationId);
        return body;
    }

    private static Map<String, Object> problemBody(IntakeOutcome outcome, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", outcome.name());
        body.put("status", outcome.httpStatus());
        body.put("correlationId", correlationId);
        return body;
    }

    private static Map<String, String> firstValues(HttpHeaders headers) {
        Map<String, String> result = new HashMap<>();
        headers.forEach((name, values) -> result.put(name, values.isEmpty() ? null : values.get(0)));
        return result;
    }

    private static String correlationId(String fromHeader) {
        if (fromHeader != null && SAFE_CORRELATION_ID.matcher(fromHeader).matches()) {
            return fromHeader;
        }
        return UUID.randomUUID().toString();
    }
}
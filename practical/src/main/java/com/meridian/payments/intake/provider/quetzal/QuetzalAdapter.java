package com.meridian.payments.intake.provider.quetzal;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.payments.intake.canonical.Amounts;
import com.meridian.payments.intake.canonical.CanonicalPaymentEvent;
import com.meridian.payments.intake.canonical.PaymentMethod;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.canonical.PolicyNumberNormalizer;
import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.provider.JsonPayloads;
import com.meridian.payments.intake.provider.MappingContext;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.PayloadMappingException;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.WebhookRequest;

public class QuetzalAdapter implements ProviderAdapter {

    private final QuetzalSignatureVerifier verifier;

    public QuetzalAdapter(QuetzalSignatureVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Provider provider() {
        return Provider.QUETZAL;
    }

    @Override
    public void verifySignature(WebhookRequest request) {
        verifier.verify(request.header(QuetzalSignatureVerifier.HEADER), request.rawBody());
    }

    @Override
    public MappingResult map(WebhookRequest request, MappingContext context) {
        JsonNode root = JsonPayloads.parse(request.rawBody());
        String eventId = JsonPayloads.requiredText(root, "/id");
        String type = JsonPayloads.requiredText(root, "/type");

        PaymentStatus status = switch (type) {
            case "charge.succeeded" -> PaymentStatus.CAPTURED;
            case "charge.failed" -> PaymentStatus.FAILED;
            case "charge.refunded" -> PaymentStatus.REFUNDED;
            default -> null;
        };
        if (status == null) {
            return new MappingResult.Ignored(eventId, "unsupported event type " + type);
        }

        String chargeId = JsonPayloads.requiredText(root, "/data/charge/id");
        String currency = Amounts.currency(JsonPayloads.requiredText(root, "/data/charge/currency"));
        String amount = Amounts.fromMinorUnits(JsonPayloads.requiredLong(root, "/data/charge/amount"), currency);
        String refundedAmount = null;
        if (status == PaymentStatus.REFUNDED) {
            Long refunded = JsonPayloads.optionalLong(root, "/data/charge/amount_refunded");
            if (refunded == null) {
                throw new PayloadMappingException("missing field /data/charge/amount_refunded");
            }
            refundedAmount = Amounts.fromMinorUnits(refunded, currency);
        }

        String rawReference = JsonPayloads.requiredText(root, "/data/charge/reference");
        String policyNumber = PolicyNumberNormalizer.normalize(rawReference, Provider.QUETZAL.country()).orElse(null);

        PaymentMethod method = paymentMethod(JsonPayloads.optionalText(root, "/data/charge/payment_method/type"));
        String failureReason = JsonPayloads.truncate(JsonPayloads.optionalText(root, "/data/charge/failure_code"), 100);

        String idempotencyKey = "QUETZAL:" + eventId;
        return new MappingResult.Mapped(new CanonicalPaymentEvent(
                CanonicalPaymentEvent.SCHEMA_VERSION,
                CanonicalPaymentEvent.eventIdFor(idempotencyKey),
                idempotencyKey,
                context.correlationId(),
                Provider.QUETZAL.country(),
                Provider.QUETZAL,
                eventId,
                chargeId,
                CanonicalPaymentEvent.paymentKey(Provider.QUETZAL, chargeId),
                policyNumber,
                JsonPayloads.truncate(rawReference, 100),
                status,
                type,
                statusChangedAt(root, status),
                context.receivedAt(),
                amount,
                refundedAmount,
                currency,
                method,
                failureReason));
    }

    private static Instant statusChangedAt(JsonNode root, PaymentStatus status) {
        Long seconds = switch (status) {
            case REFUNDED -> JsonPayloads.optionalLong(root, "/data/charge/refunded_at");
            case CAPTURED -> JsonPayloads.optionalLong(root, "/data/charge/paid_at");
            default -> null;
        };
        if (seconds == null) {
            seconds = JsonPayloads.requiredLong(root, "/created");
        }
        return Instant.ofEpochSecond(seconds);
    }

    private static PaymentMethod paymentMethod(String type) {
        return "card".equals(type) ? PaymentMethod.CARD : PaymentMethod.OTHER;
    }
}

package com.meridian.payments.intake.provider.andina;

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

public class AndinaAdapter implements ProviderAdapter {

    private final AndinaSignatureVerifier verifier;

    public AndinaAdapter(AndinaSignatureVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Provider provider() {
        return Provider.ANDINA;
    }

    @Override
    public void verifySignature(WebhookRequest request) {
        verifier.verify(
                request.header(AndinaSignatureVerifier.TIMESTAMP_HEADER),
                request.header(AndinaSignatureVerifier.SIGNATURE_HEADER),
                request.rawBody());
    }

    @Override
    public MappingResult map(WebhookRequest request, MappingContext context) {
        JsonNode root = JsonPayloads.parse(request.rawBody());
        String notificationId = JsonPayloads.requiredText(root, "/notificacion_id");
        String transactionId = JsonPayloads.requiredText(root, "/transaccion/id");
        String providerStatus = JsonPayloads.requiredText(root, "/transaccion/estado");

        PaymentStatus status = switch (providerStatus) {
            case "PENDIENTE" -> PaymentStatus.PENDING;
            case "APROBADA" -> PaymentStatus.CAPTURED;
            case "RECHAZADA" -> PaymentStatus.FAILED;
            case "REVERSADA" -> PaymentStatus.REFUNDED;
            default -> throw new PayloadMappingException("unknown /transaccion/estado");
        };

        String currency = Amounts.currency(JsonPayloads.requiredText(root, "/transaccion/moneda"));
        String amount = Amounts.fromMajorUnits(JsonPayloads.requiredText(root, "/transaccion/valor"), currency);
        String rawReference = JsonPayloads.requiredText(root, "/transaccion/referencia");
        String policyNumber = PolicyNumberNormalizer.normalize(rawReference, Provider.ANDINA.country()).orElse(null);
        Instant statusChangedAt = JsonPayloads.requiredOffsetDateTime(root, "/transaccion/fecha_transaccion");
        String fullAmountIfReversed = status == PaymentStatus.REFUNDED ? amount : null;
        PaymentMethod method = paymentMethod(JsonPayloads.optionalText(root, "/transaccion/medio_pago"));
        String reversalReason = JsonPayloads.truncate(JsonPayloads.optionalText(root, "/transaccion/motivo_reverso"), 100);
        
        String idempotencyKey = "ANDINA:" + transactionId + ":" + providerStatus;
        return new MappingResult.Mapped(new CanonicalPaymentEvent(
            CanonicalPaymentEvent.SCHEMA_VERSION,
            CanonicalPaymentEvent.eventIdFor(idempotencyKey),
            idempotencyKey,
            context.correlationId(),
            Provider.ANDINA.country(),
            Provider.ANDINA,
            notificationId,
            transactionId,
            CanonicalPaymentEvent.paymentKey(Provider.ANDINA, transactionId),
            policyNumber,
            JsonPayloads.truncate(rawReference, 100),
            status,
            providerStatus,
            statusChangedAt,
            context.receivedAt(),
            amount,
            fullAmountIfReversed,
            currency,
            method,
            reversalReason));
    }

    private static PaymentMethod paymentMethod(String providerMethod) {
        if (providerMethod == null) {
            return PaymentMethod.OTHER;
        }
        return switch (providerMethod) {
            case "TARJETA" -> PaymentMethod.CARD;
            case "PSE" -> PaymentMethod.BANK_TRANSFER;
            case "EFECTIVO" -> PaymentMethod.CASH;
            default -> PaymentMethod.OTHER;
        };
    }
}

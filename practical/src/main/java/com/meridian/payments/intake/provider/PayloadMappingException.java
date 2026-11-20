package com.meridian.payments.intake.provider;

public class PayloadMappingException extends RuntimeException {

    public PayloadMappingException(String reason) {
        super(reason);
    }

    public PayloadMappingException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

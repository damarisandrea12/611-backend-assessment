package com.meridian.payments.intake.provider;

public class SignatureVerificationException extends RuntimeException {

    public SignatureVerificationException(String reason) {
        super(reason);
    }
}

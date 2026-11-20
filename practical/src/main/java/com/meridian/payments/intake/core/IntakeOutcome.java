package com.meridian.payments.intake.core;

public enum IntakeOutcome {
    ACCEPTED(200),
    DUPLICATE(200),
    IGNORED(200),
    INVALID_SIGNATURE(401),
    UNPROCESSABLE(400),
    UNKNOWN_PROVIDER(404),
    PAYLOAD_TOO_LARGE(413),
    UNAVAILABLE(503);

    private final int httpStatus;

    IntakeOutcome(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean accepted() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}

package com.meridian.payments.intake.canonical;

/** Rank stops late events from moving a payment backwards (FR5). */
public enum PaymentStatus {
    PENDING(0),
    CAPTURED(1),
    FAILED(1),
    REFUNDED(2);

    private final int rank;

    PaymentStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}

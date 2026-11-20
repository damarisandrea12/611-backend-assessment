package com.meridian.payments.intake.messaging;

public interface EventPublisher {

    void publish(OutboundMessage message) throws PublishException;
}

package com.meridian.payments.intake.messaging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryEventPublisher implements EventPublisher {

    private final List<OutboundMessage> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(OutboundMessage message) {
        published.add(message);
    }

    public List<OutboundMessage> published() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
    }
}

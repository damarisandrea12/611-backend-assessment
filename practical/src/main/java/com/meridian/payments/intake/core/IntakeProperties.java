package com.meridian.payments.intake.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "intake")
public record IntakeProperties(
        @DefaultValue("3s") Duration publishTimeout,
        @DefaultValue("65536") int maxBodyBytes) {
}

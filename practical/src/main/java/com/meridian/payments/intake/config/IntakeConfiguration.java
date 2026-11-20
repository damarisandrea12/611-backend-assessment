package com.meridian.payments.intake.config;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.meridian.payments.intake.core.IntakeProperties;
import com.meridian.payments.intake.core.IntakeService;
import com.meridian.payments.intake.idempotency.IdempotencyStore;
import com.meridian.payments.intake.idempotency.InMemoryIdempotencyStore;
import com.meridian.payments.intake.messaging.EventPublisher;
import com.meridian.payments.intake.messaging.InMemoryEventPublisher;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.provider.andina.AndinaSignatureVerifier;
import com.meridian.payments.intake.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.provider.quetzal.QuetzalSignatureVerifier;
import com.meridian.payments.intake.secrets.EnvironmentSecretProvider;
import com.meridian.payments.intake.secrets.SecretProvider;

@Configuration
public class IntakeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecretProvider secretProvider() {
        return new EnvironmentSecretProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher() {
        return new InMemoryEventPublisher();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    public QuetzalAdapter quetzalAdapter(SecretProvider secrets, Clock clock) {
        return new QuetzalAdapter(new QuetzalSignatureVerifier(secrets, clock));
    }

    @Bean
    public AndinaAdapter andinaAdapter(SecretProvider secrets, Clock clock) {
        return new AndinaAdapter(new AndinaSignatureVerifier(secrets, clock));
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService publishExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public IntakeService intakeService(List<ProviderAdapter> adapters, EventPublisher publisher,
                                       IdempotencyStore idempotencyStore, ExecutorService publishExecutor,
                                       IntakeProperties properties, Clock clock) {
        return new IntakeService(adapters, publisher, idempotencyStore, publishExecutor, properties, clock);
    }
}

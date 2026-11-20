package com.meridian.payments.intake.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EnvironmentSecretProviderTest {

    @Test
    void readsCommaSeparatedSecretsForRotation() {
        Map<String, String> env = Map.of("QUETZAL_WEBHOOK_SECRETS", "new-secret, old-secret");
        EnvironmentSecretProvider provider = new EnvironmentSecretProvider(env::get);
        assertThat(provider.secrets("quetzal-webhook")).containsExactly("new-secret", "old-secret");
    }

    @Test
    void returnsEmptyWhenNotConfigured() {
        EnvironmentSecretProvider provider = new EnvironmentSecretProvider(name -> null);
        assertThat(provider.secrets("andina-webhook")).isEmpty();
    }
}

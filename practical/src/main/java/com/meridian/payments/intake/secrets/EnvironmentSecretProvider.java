package com.meridian.payments.intake.secrets;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

public class EnvironmentSecretProvider implements SecretProvider {

    private final UnaryOperator<String> environment;

    public EnvironmentSecretProvider() {
        this(System::getenv);
    }

    EnvironmentSecretProvider(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public List<String> secrets(String name) {
        String variable = name.toUpperCase(Locale.ROOT).replace('-', '_') + "_SECRETS";
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(s -> s.strip()).filter(s -> !s.isEmpty()).toList();
    }
}

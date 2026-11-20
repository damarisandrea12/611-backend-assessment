package com.meridian.payments.intake.canonical;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class PolicyNumberNormalizer {

    private static final Pattern POLICY_NUMBER = Pattern.compile("^POL-(MX|CO|CL)-\\d{9}$");
    private static final Pattern NINE_DIGITS = Pattern.compile("^\\d{9}$");

    private PolicyNumberNormalizer() {
    }

    public static Optional<String> normalize(String rawReference, Country expectedCountry) {
        if (rawReference == null) {
            return Optional.empty();
        }
        String value = rawReference.strip().toUpperCase(Locale.ROOT);
        if (NINE_DIGITS.matcher(value).matches()) {
            value = "POL-" + expectedCountry.name() + "-" + value;
        }
        if (!POLICY_NUMBER.matcher(value).matches()) {
            return Optional.empty();
        }
        if (!value.startsWith("POL-" + expectedCountry.name() + "-")) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}

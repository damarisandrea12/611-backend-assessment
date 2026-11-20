package com.meridian.payments.intake.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyNumberNormalizerTest {

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(nullValues = "null", value = {
            "POL-MX-000412785, MX, POL-MX-000412785",
            "pol-mx-000412785, MX, POL-MX-000412785",
            "000412785, MX, POL-MX-000412785",
            "000187342, CO, POL-CO-000187342",
            "POL-CL-000093311, CL, POL-CL-000093311",
            "POL-CO-000187342, MX, null",
            "POL-CO-0001873420, CO, null",
            "00018734, CO, null",
            "POL_CO_000187342, CO, null",
            "'', CO, null"})
    void normalizes(String raw, Country country, String expected) {
        assertThat(PolicyNumberNormalizer.normalize(raw, country).orElse(null)).isEqualTo(expected);
    }

    @Test
    void nullIsUnresolved() {
        assertThat(PolicyNumberNormalizer.normalize(null, Country.CO)).isEmpty();
    }
}

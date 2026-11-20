package com.meridian.payments.intake.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meridian.payments.intake.provider.PayloadMappingException;

@DisplayName("Amounts: minor/major units per currency, no floating point")
class AmountsTest {

    @Test
    void convertsMinorUnitsUsingTheCurrencyFractionDigits() {
        assertThat(Amounts.fromMinorUnits(125000, "MXN")).isEqualTo("1250.00");
        assertThat(Amounts.fromMinorUnits(1, "MXN")).isEqualTo("0.01");
        assertThat(Amounts.fromMinorUnits(45990, "CLP")).isEqualTo("45990"); 
    }

    @Test
    void parsesMajorUnits() {
        assertThat(Amounts.fromMajorUnits("185000.00", "COP")).isEqualTo("185000.00");
        assertThat(Amounts.fromMajorUnits("185000", "COP")).isEqualTo("185000.00");
        assertThat(Amounts.fromMajorUnits("45990", "CLP")).isEqualTo("45990");
    }

    @Test
    void neverRoundsSilently() {
        assertThatThrownBy(() -> Amounts.fromMajorUnits("45990.50", "CLP")).isInstanceOf(PayloadMappingException.class);
    }

    @Test
    void rejectsNegativeAndUnsupported() {
        assertThatThrownBy(() -> Amounts.fromMinorUnits(-1, "MXN")).isInstanceOf(PayloadMappingException.class);
        assertThatThrownBy(() -> Amounts.currency("usd")).isInstanceOf(PayloadMappingException.class);
        assertThatThrownBy(() -> Amounts.currency(null)).isInstanceOf(PayloadMappingException.class);
        assertThat(Amounts.currency("mxn")).isEqualTo("MXN");
    }
}

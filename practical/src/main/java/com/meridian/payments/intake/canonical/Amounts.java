package com.meridian.payments.intake.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

import com.meridian.payments.intake.provider.PayloadMappingException;

/** Money is always BigDecimal, never floating point. */
public final class Amounts {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("MXN", "COP", "CLP");

    private Amounts() {
    }

    public static String currency(String code) {
        if (code == null) {
            throw new PayloadMappingException("currency is missing");
        }
        String upper = code.strip().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(upper)) {
            throw new PayloadMappingException("unsupported currency " + upper);
        }
        return upper;
    }

    public static String fromMinorUnits(long minorUnits, String currency) {
        if (minorUnits < 0) {
            throw new PayloadMappingException("negative amount");
        }
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return BigDecimal.valueOf(minorUnits, digits).toPlainString();
    }
    /** Extra decimals are rejected, never rounded: we must not change money silently. */
    public static String fromMajorUnits(String value, String currency) {
        if (value == null || !value.matches("^\\d{1,14}(\\.\\d{1,2})?$")) {
            throw new PayloadMappingException("invalid amount format");
        }
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        try {
            return new BigDecimal(value).setScale(digits, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new PayloadMappingException("amount has more decimals than " + currency + " allows");
        }
    }
}

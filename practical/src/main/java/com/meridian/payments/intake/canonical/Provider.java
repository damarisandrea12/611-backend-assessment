package com.meridian.payments.intake.canonical;

import java.util.Arrays;
import java.util.Optional;

public enum Provider {
    QUETZAL("quetzal", Country.MX),
    ANDINA("andina", Country.CO),
    AUSTRAL("austral", Country.CL);

    private final String pathId;
    private final Country country;

    Provider(String pathId, Country country) {
        this.pathId = pathId;
        this.country = country;
    }

    public String pathId() {
        return pathId;
    }

    public Country country() {
        return country;
    }

    public static Optional<Provider> fromPathId(String pathId) {
        return Arrays.stream(values()).filter(p -> p.pathId.equals(pathId)).findFirst();
    }
}

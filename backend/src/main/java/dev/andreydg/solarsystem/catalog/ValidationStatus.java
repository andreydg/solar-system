package dev.andreydg.solarsystem.catalog;

import java.util.Locale;

public enum ValidationStatus {
    PENDING,
    VALIDATED,
    FAILED;

    public static ValidationStatus fromStoredValue(Object value) {
        if (value == null) {
            return PENDING;
        }

        return ValidationStatus.valueOf(value.toString().toUpperCase(Locale.ROOT));
    }

    public String storedValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

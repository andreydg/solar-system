package dev.andreydg.solarsystem.catalog;

import java.util.Locale;

public enum BodyId {
    MERCURY,
    VENUS,
    EARTH,
    MARS,
    JUPITER,
    SATURN,
    URANUS,
    NEPTUNE;

    public static BodyId fromApiValue(String value) {
        return BodyId.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

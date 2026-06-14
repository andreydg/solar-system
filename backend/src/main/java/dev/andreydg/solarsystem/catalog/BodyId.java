package dev.andreydg.solarsystem.catalog;

import java.time.Instant;
import java.util.Locale;

public enum BodyId {
    MERCURY,
    VENUS,
    EARTH,
    MARS,
    JUPITER,
    SATURN,
    URANUS,
    NEPTUNE,
    CERES,
    VESTA,
    ENCKE,
    HALLEY;

    public static BodyId fromApiValue(String value) {
        return BodyId.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isSmallBody() {
        return switch (this) {
            case CERES, VESTA, ENCKE, HALLEY -> true;
            default -> false;
        };
    }

    public String horizonsCommand() {
        return switch (this) {
            case MERCURY -> "199";
            case VENUS -> "299";
            case EARTH -> "399";
            case MARS -> "499";
            case JUPITER -> "599";
            case SATURN -> "699";
            case URANUS -> "799";
            case NEPTUNE -> "899";
            case CERES -> "1;";
            case VESTA -> "4;";
            case ENCKE -> "90000091;";
            case HALLEY -> "90000030;";
        };
    }

    public double typicalOrbitDays() {
        return switch (this) {
            case CERES -> 1680.0;
            case VESTA -> 1325.0;
            case ENCKE -> 1204.0;
            case HALLEY -> 27520.0;
            default -> throw new IllegalArgumentException("No orbit period for " + this);
        };
    }

    public Instant trajectoryEpoch() {
        return Instant.parse("2026-01-01T00:00:00Z");
    }
}

package dev.andreydg.solarsystem.catalog;

import java.util.Locale;

public enum EventType {
    CLOSEST_APPROACH,
    FARTHEST_APPROACH,
    OPPOSITION,
    CONJUNCTION,
    GREATEST_ELONGATION,
    STATIONARY,
    RETROGRADE_START,
    RETROGRADE_END,
    TRANSIT,
    BRIGHTEST_APPROACH;

    public static EventType fromApiValue(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "closestapproach", "closest_approach", "closest-approach" -> CLOSEST_APPROACH;
            case "farthestapproach", "farthest_approach", "farthest-approach" -> FARTHEST_APPROACH;
            case "opposition" -> OPPOSITION;
            case "conjunction" -> CONJUNCTION;
            case "greatestelongation", "greatest_elongation", "greatest-elongation" -> GREATEST_ELONGATION;
            case "stationary" -> STATIONARY;
            case "retrogradestart", "retrograde_start", "retrograde-start" -> RETROGRADE_START;
            case "retrogradeend", "retrograde_end", "retrograde-end" -> RETROGRADE_END;
            case "transit" -> TRANSIT;
            case "brightestapproach", "brightest_approach", "brightest-approach" -> BRIGHTEST_APPROACH;
            default -> throw new IllegalArgumentException("Unsupported event type: " + value);
        };
    }

    public String apiValue() {
        return switch (this) {
            case CLOSEST_APPROACH -> "closestApproach";
            case FARTHEST_APPROACH -> "farthestApproach";
            case OPPOSITION -> "opposition";
            case CONJUNCTION -> "conjunction";
            case GREATEST_ELONGATION -> "greatestElongation";
            case STATIONARY -> "stationary";
            case RETROGRADE_START -> "retrogradeStart";
            case RETROGRADE_END -> "retrogradeEnd";
            case TRANSIT -> "transit";
            case BRIGHTEST_APPROACH -> "brightestApproach";
        };
    }

    public boolean supportsJplValidation() {
        return switch (this) {
            case CLOSEST_APPROACH, FARTHEST_APPROACH, OPPOSITION, CONJUNCTION, GREATEST_ELONGATION -> true;
            default -> false;
        };
    }
}

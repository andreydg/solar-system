package dev.andreydg.solarsystem.catalog;

import java.time.Duration;
import java.time.Instant;

/** Resource limits are enforced below HTTP so internal callers cannot bypass them. */
public final class CatalogLimits {
    public static final Instant MIN_TIME = Instant.parse("1600-01-01T00:00:00Z");
    public static final Instant MAX_TIME = Instant.parse("3001-01-01T00:00:00Z");
    public static final Duration MAX_WINDOW = Duration.ofDays(31 * 366L);
    private CatalogLimits() {}

    public static void checkTime(Instant time) {
        if (time == null || time.isBefore(MIN_TIME) || !time.isBefore(MAX_TIME)) {
            throw new IllegalArgumentException("Date must be within 1600–3000");
        }
    }

    public static void checkRange(Instant from, Instant to) {
        checkTime(from);
        checkTime(to);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("Range must be increasing and no longer than 31 years");
        }
    }
}

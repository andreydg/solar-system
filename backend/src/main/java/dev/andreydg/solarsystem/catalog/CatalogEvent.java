package dev.andreydg.solarsystem.catalog;

import java.time.Instant;

public record CatalogEvent(
    String id,
    EventType type,
    BodyId bodyA,
    BodyId bodyB,
    Instant computedTimeUtc,
    Double computedDistanceAu,
    Double computedAngleDeg,
    Double computedMagnitude,
    String computedSource,
    Instant validatedTimeUtc,
    Double validatedDistanceAu,
    Double validatedAngleDeg,
    Double validatedMagnitude,
    String validatedSource,
    ValidationStatus validationStatus,
    Instant rangeStartUtc,
    Instant rangeEndUtc,
    Instant generatedAtUtc,
    Instant jplCheckedAtUtc,
    Double jplDeltaKm,
    String jplRawSummary,
    int algorithmVersion
) {
    public static final int CURRENT_ALGORITHM_VERSION = 2;
    public CatalogEvent(String id, EventType type, BodyId bodyA, BodyId bodyB, Instant computedTimeUtc,
        Double computedDistanceAu, Double computedAngleDeg, Double computedMagnitude, String computedSource,
        Instant validatedTimeUtc, Double validatedDistanceAu, Double validatedAngleDeg, Double validatedMagnitude,
        String validatedSource, ValidationStatus validationStatus, Instant rangeStartUtc, Instant rangeEndUtc,
        Instant generatedAtUtc, Instant jplCheckedAtUtc, Double jplDeltaKm, String jplRawSummary) {
        this(id, type, bodyA, bodyB, computedTimeUtc, computedDistanceAu, computedAngleDeg, computedMagnitude,
            computedSource, validatedTimeUtc, validatedDistanceAu, validatedAngleDeg, validatedMagnitude,
            validatedSource, validationStatus, rangeStartUtc, rangeEndUtc, generatedAtUtc, jplCheckedAtUtc,
            jplDeltaKm, jplRawSummary, CURRENT_ALGORITHM_VERSION);
    }
    public CatalogEvent validated(Instant time, Double distance, Double angle, Double magnitude,
        Double deltaKm, String summary) {
        return new CatalogEvent(id, type, bodyA, bodyB, computedTimeUtc, computedDistanceAu, computedAngleDeg,
            computedMagnitude, computedSource, time, distance, angle, magnitude,
            type == EventType.BRIGHTEST_APPROACH ? "JPL_GEOMETRY_MODEL_MAGNITUDE" : "JPL_HORIZONS", ValidationStatus.VALIDATED,
            rangeStartUtc, rangeEndUtc, generatedAtUtc, Instant.now(), deltaKm, summary, algorithmVersion);
    }
    public CatalogEvent failed(String summary) {
        return new CatalogEvent(id, type, bodyA, bodyB, computedTimeUtc, computedDistanceAu, computedAngleDeg,
            computedMagnitude, computedSource, null, null, null, null, null, ValidationStatus.FAILED,
            rangeStartUtc, rangeEndUtc, generatedAtUtc, Instant.now(), null, summary, algorithmVersion);
    }
    /** Data unavailable is not evidence that the provisional event is geometrically false. */
    public CatalogEvent unavailable(String summary) {
        if (validationStatus == ValidationStatus.VALIDATED) return this;
        return new CatalogEvent(id, type, bodyA, bodyB, computedTimeUtc, computedDistanceAu, computedAngleDeg,
            computedMagnitude, computedSource, null, null, null, null, null, ValidationStatus.UNAVAILABLE,
            rangeStartUtc, rangeEndUtc, generatedAtUtc, Instant.now(), null, summary, algorithmVersion);
    }
    public CatalogEvent pending() {
        return new CatalogEvent(id, type, bodyA, bodyB, computedTimeUtc, computedDistanceAu, computedAngleDeg,
            computedMagnitude, computedSource, null, null, null, null, null, ValidationStatus.PENDING,
            rangeStartUtc, rangeEndUtc, generatedAtUtc, null, null, null, algorithmVersion);
    }
    public Instant displayTimeUtc() {
        return validatedTimeUtc == null ? computedTimeUtc : validatedTimeUtc;
    }

    public Double displayDistanceAu() {
        return validatedDistanceAu == null ? computedDistanceAu : validatedDistanceAu;
    }

    public Double displayAngleDeg() {
        return validatedAngleDeg == null ? computedAngleDeg : validatedAngleDeg;
    }

    public Double displayMagnitude() {
        return validatedMagnitude == null ? computedMagnitude : validatedMagnitude;
    }

    public String displaySource() {
        return validatedSource == null ? computedSource : validatedSource;
    }
}

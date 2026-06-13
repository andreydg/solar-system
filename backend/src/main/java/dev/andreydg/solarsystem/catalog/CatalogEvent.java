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
    String jplRawSummary
) {
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

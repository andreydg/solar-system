package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventCatalogService;

public record EventDto(
    String id,
    String type,
    String bodyA,
    String bodyB,
    String timeUtc,
    Double distanceAu,
    Double distanceKm,
    Double angleDeg,
    Double magnitude,
    String source,
    String validationStatus,
    String computedTimeUtc,
    Double computedDistanceAu,
    Double computedAngleDeg,
    Double computedMagnitude,
    String computedSource,
    String validatedTimeUtc,
    Double validatedDistanceAu,
    Double validatedAngleDeg,
    Double validatedMagnitude,
    String validatedSource,
    String rangeStartUtc,
    String rangeEndUtc,
    String generatedAtUtc,
    String jplCheckedAtUtc,
    Double jplDeltaKm,
    String jplRawSummary
) {
    public static EventDto from(CatalogEvent event) {
        return new EventDto(
            event.id(),
            event.type().apiValue(),
            event.bodyA().apiValue(),
            event.bodyB().apiValue(),
            event.displayTimeUtc().toString(),
            event.displayDistanceAu(),
            event.displayDistanceAu() == null ? null : event.displayDistanceAu() * EventCatalogService.AU_KM,
            event.displayAngleDeg(),
            event.displayMagnitude(),
            event.displaySource(),
            event.validationStatus().storedValue(),
            event.computedTimeUtc().toString(),
            event.computedDistanceAu(),
            event.computedAngleDeg(),
            event.computedMagnitude(),
            event.computedSource(),
            event.validatedTimeUtc() == null ? null : event.validatedTimeUtc().toString(),
            event.validatedDistanceAu(),
            event.validatedAngleDeg(),
            event.validatedMagnitude(),
            event.validatedSource(),
            event.rangeStartUtc().toString(),
            event.rangeEndUtc().toString(),
            event.generatedAtUtc().toString(),
            event.jplCheckedAtUtc() == null ? null : event.jplCheckedAtUtc().toString(),
            event.jplDeltaKm(),
            event.jplRawSummary()
        );
    }
}

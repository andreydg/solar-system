package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.jpl.JplVector;

public record JplVectorDto(
    String body,
    String requestedTimeUtc,
    double xAu,
    double yAu,
    double zAu,
    String sourceDate,
    String rawSummary
) {
    public static JplVectorDto from(JplVector vector) {
        return new JplVectorDto(
            vector.body().apiValue(),
            vector.requestedTimeUtc().toString(),
            vector.positionAu().x(),
            vector.positionAu().y(),
            vector.positionAu().z(),
            vector.sourceDate(),
            vector.rawSummary()
        );
    }
}

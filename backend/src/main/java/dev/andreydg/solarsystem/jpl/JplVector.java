package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.time.Instant;

public record JplVector(
    BodyId body,
    Instant requestedTimeUtc,
    Vector3Au positionAu,
    String sourceDate,
    String rawSummary
) {
}

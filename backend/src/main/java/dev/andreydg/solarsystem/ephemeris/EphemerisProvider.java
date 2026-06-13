package dev.andreydg.solarsystem.ephemeris;

import dev.andreydg.solarsystem.catalog.BodyId;
import java.time.Instant;

public interface EphemerisProvider {
    Vector3Au heliocentricPosition(BodyId body, Instant timeUtc);

    String source();
}

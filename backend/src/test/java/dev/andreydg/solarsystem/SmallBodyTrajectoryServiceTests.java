package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample;
import dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmallBodyTrajectoryServiceTests {
    @Test
    void mapsEnckeDatesOutsideCacheOntoOrbitPhase() {
        List<TrajectorySample> trajectory = List.of(
            new TrajectorySample(Instant.parse("2024-05-09T00:00:00Z"), new Vector3Au(1.0, 0.0, 0.0)),
            new TrajectorySample(Instant.parse("2026-01-01T00:00:00Z"), new Vector3Au(0.0, 1.0, 0.0)),
            new TrajectorySample(Instant.parse("2027-08-26T00:00:00Z"), new Vector3Au(-1.0, 0.0, 0.0))
        );

        Instant insideCache = SmallBodyTrajectoryService.resolveQueryTime(
            BodyId.ENCKE,
            trajectory,
            Instant.parse("2026-06-13T00:00:00Z")
        );
        Instant afterCache = SmallBodyTrajectoryService.resolveQueryTime(
            BodyId.ENCKE,
            trajectory,
            Instant.parse("2027-09-15T00:00:00Z")
        );

        assertThat(insideCache).isEqualTo(Instant.parse("2026-06-13T00:00:00Z"));
        assertThat(afterCache).isBefore(Instant.parse("2027-08-26T00:00:00Z"));
        assertThat(afterCache).isAfter(Instant.parse("2024-05-09T00:00:00Z"));
    }
}

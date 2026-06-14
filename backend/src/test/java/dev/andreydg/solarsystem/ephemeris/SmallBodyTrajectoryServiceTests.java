package dev.andreydg.solarsystem.ephemeris;

import static org.assertj.core.api.Assertions.assertThat;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
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

    @Nested
    class InterpolationTests {
        private static final List<TrajectorySample> THREE_POINT_TRAJECTORY = List.of(
            new TrajectorySample(Instant.parse("2026-01-01T00:00:00Z"), new Vector3Au(1.0, 0.0, 0.0)),
            new TrajectorySample(Instant.parse("2026-07-01T00:00:00Z"), new Vector3Au(0.0, 1.0, 0.0)),
            new TrajectorySample(Instant.parse("2027-01-01T00:00:00Z"), new Vector3Au(-1.0, 0.0, 0.0))
        );

        @Test
        void returnsExactPositionAtFirstBoundary() {
            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                THREE_POINT_TRAJECTORY, Instant.parse("2026-01-01T00:00:00Z")
            );

            assertThat(result).isPresent();
            assertThat(result.get().x()).isEqualTo(1.0);
            assertThat(result.get().y()).isEqualTo(0.0);
            assertThat(result.get().z()).isEqualTo(0.0);
        }

        @Test
        void returnsExactPositionAtLastBoundary() {
            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                THREE_POINT_TRAJECTORY, Instant.parse("2027-01-01T00:00:00Z")
            );

            assertThat(result).isPresent();
            assertThat(result.get().x()).isEqualTo(-1.0);
            assertThat(result.get().y()).isEqualTo(0.0);
        }

        @Test
        void returnsExactPositionAtMiddleSample() {
            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                THREE_POINT_TRAJECTORY, Instant.parse("2026-07-01T00:00:00Z")
            );

            assertThat(result).isPresent();
            assertThat(result.get().x()).isEqualTo(0.0);
            assertThat(result.get().y()).isEqualTo(1.0);
        }

        @Test
        void interpolatesLinearlyBetweenSamples() {
            // Midpoint between first (2026-01-01) and second (2026-07-01) should be ~(0.5, 0.5, 0.0)
            Instant midpoint = Instant.parse("2026-04-02T00:00:00Z"); // approximately halfway
            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                THREE_POINT_TRAJECTORY, midpoint
            );

            assertThat(result).isPresent();
            assertThat(result.get().x()).isBetween(0.4, 0.6);
            assertThat(result.get().y()).isBetween(0.4, 0.6);
        }

        @Test
        void clampsBeforeRange() {
            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                THREE_POINT_TRAJECTORY, Instant.parse("2020-01-01T00:00:00Z")
            );

            assertThat(result).isPresent();
            assertThat(result.get().x()).isEqualTo(1.0);
        }

        @Test
        void clampsAfterRange() {
            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                THREE_POINT_TRAJECTORY, Instant.parse("2030-01-01T00:00:00Z")
            );

            assertThat(result).isPresent();
            assertThat(result.get().x()).isEqualTo(-1.0);
        }

        @Test
        void returnsEmptyForEmptyTrajectory() {
            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                List.of(), Instant.parse("2026-06-01T00:00:00Z")
            );

            assertThat(result).isEmpty();
        }

        @Test
        void returnsSinglePositionForSingleSample() {
            List<TrajectorySample> single = List.of(
                new TrajectorySample(Instant.parse("2026-06-01T00:00:00Z"), new Vector3Au(2.0, 3.0, 4.0))
            );

            Optional<Vector3Au> result = SmallBodyTrajectoryService.interpolateWithinRange(
                single, Instant.parse("2026-09-01T00:00:00Z")
            );

            assertThat(result).isPresent();
            assertThat(result.get().x()).isEqualTo(2.0);
            assertThat(result.get().y()).isEqualTo(3.0);
            assertThat(result.get().z()).isEqualTo(4.0);
        }
    }
}

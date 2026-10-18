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
    void wholeWindowWithoutEphemerisStaysPermanentAndDoesNotCacheAnEmptyTrajectory() {
        var client = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplHorizonsClient.class);
        var missing = new dev.andreydg.solarsystem.jpl.JplHorizonsException("No ephemeris for requested window");
        org.mockito.Mockito.when(client.vectors(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenThrow(missing);
        var files = org.mockito.Mockito.mock(TrajectoryFileStore.class);
        var service = new SmallBodyTrajectoryService(new JplEphemerisProvider(client), files,
            new dev.andreydg.solarsystem.config.TrajectoryCacheProperties("/unused", true));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getTrajectory(BodyId.HALLEY))
            .isSameAs(missing).satisfies(error -> assertThat(((dev.andreydg.solarsystem.jpl.JplHorizonsException) error).isTransient()).isFalse());
        assertThat(service.isCached(BodyId.HALLEY)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(files);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).vectors(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }


    @Test
    void laterNoEphemerisPreservesAndCachesTheAvailablePartOfTheTrajectory() {
        var client = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplHorizonsClient.class);
        var time = Instant.parse("2000-01-01T00:00:00Z");
        var point = new Vector3Au(1, 2, 3);
        org.mockito.Mockito.when(client.vectors(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of(new dev.andreydg.solarsystem.jpl.JplVector(BodyId.HALLEY, time, point, "", "")))
            .thenThrow(new dev.andreydg.solarsystem.jpl.JplHorizonsException("No ephemeris for requested window"));
        var files = org.mockito.Mockito.mock(TrajectoryFileStore.class);
        var service = new SmallBodyTrajectoryService(new JplEphemerisProvider(client), files,
            new dev.andreydg.solarsystem.config.TrajectoryCacheProperties("/unused", true));
        var trajectory = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.getTrajectory(BodyId.HALLEY));
        assertThat(trajectory).containsExactly(new TrajectorySample(time, point));
        assertThat(service.isCached(BodyId.HALLEY)).isTrue();
        assertThat(service.getTrajectory(BodyId.HALLEY)).isSameAs(trajectory);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).vectors(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(files).save(org.mockito.ArgumentMatchers.eq(BodyId.HALLEY),
            org.mockito.ArgumentMatchers.eq(trajectory), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void genuinelyEmptyTablesRemainTransientAndAreNotCachedOrSaved() {
        var client = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplHorizonsClient.class);
        org.mockito.Mockito.when(client.vectors(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        var files = org.mockito.Mockito.mock(TrajectoryFileStore.class);
        var service = new SmallBodyTrajectoryService(new JplEphemerisProvider(client), files,
            new dev.andreydg.solarsystem.config.TrajectoryCacheProperties("/unused", true));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getTrajectory(BodyId.HALLEY))
            .isInstanceOf(dev.andreydg.solarsystem.jpl.JplHorizonsException.class)
            .hasMessage("Empty trajectory")
            .satisfies(error -> assertThat(((dev.andreydg.solarsystem.jpl.JplHorizonsException) error).isTransient()).isTrue());
        assertThat(service.isCached(BodyId.HALLEY)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(files);
    }

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

    @Test void orbitWrappingPreservesFractionalDaysAndInterpolatedMotion() {
        Instant first = Instant.parse("1988-01-01T00:00:00Z");
        long period = Math.round(BodyId.HALLEY.typicalOrbitDays() * 86_400_000);
        var path = List.of(new TrajectorySample(first, new Vector3Au(0, 0, 0)),
            new TrajectorySample(first.plusMillis(period), new Vector3Au(10, 0, 0)));
        for (long lap : new long[] {-3, -1, 1, 3}) {
            Instant query = first.plusMillis(lap * period).plusSeconds(123 * 86400L + 12345);
            Instant mapped = SmallBodyTrajectoryService.resolveQueryTime(BodyId.HALLEY, path, query);
            assertThat(mapped).isEqualTo(first.plusSeconds(123 * 86400L + 12345));
            Instant next = SmallBodyTrajectoryService.resolveQueryTime(BodyId.HALLEY, path, query.plusSeconds(3600));
            assertThat(next).isEqualTo(mapped.plusSeconds(3600));
            assertThat(SmallBodyTrajectoryService.interpolateWithinRange(path, next).orElseThrow().x())
                .isGreaterThan(SmallBodyTrajectoryService.interpolateWithinRange(path, mapped).orElseThrow().x());
        }
        assertThat(SmallBodyTrajectoryService.resolveQueryTime(BodyId.HALLEY, path, first.minusMillis(1)))
            .isEqualTo(first.plusMillis(period - 1));
        assertThat(SmallBodyTrajectoryService.resolveQueryTime(BodyId.HALLEY, path, first.plusMillis(period + 1)))
            .isEqualTo(first.plusMillis(1));
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

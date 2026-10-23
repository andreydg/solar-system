package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.andreydg.solarsystem.ephemeris.*;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class EventWindowEdgeTests {
    private static final Instant EDGE = Instant.parse("2061-07-28T12:00:00Z");
    private EventCatalogService service(EventType type, Instant extremum, InMemoryEventCatalogRepository repository) {
        var provider = mock(CompositeEphemerisProvider.class);
        when(provider.sourceFor(any())).thenReturn("SYNTHETIC_EDGE_REGRESSION");
        when(provider.heliocentricPosition(any(), any())).thenAnswer(call -> {
            BodyId body = call.getArgument(0); Instant time = call.getArgument(1);
            return new Vector3Au(body == BodyId.EARTH ? 0 : distance(type, extremum, time), 0, 0);
        });
        when(provider.heliocentricDistanceSamples(any(), any(), any(), any())).thenAnswer(call -> {
            Instant from = call.getArgument(1), to = call.getArgument(2); Duration step = call.getArgument(3);
            var samples = new ArrayList<JplEphemerisProvider.HeliocentricDistanceSample>();
            for (Instant t = from; !t.isAfter(to); t = t.plus(step))
                samples.add(new JplEphemerisProvider.HeliocentricDistanceSample(t, distance(type, extremum, t)));
            return samples;
        });
        return new EventCatalogService(provider, repository);
    }
    private static double distance(EventType type, Instant peak, Instant time) {
        double years = Duration.between(peak, time).toSeconds() / 86400.0 / 3650;
        return 20 + (type == EventType.FARTHEST_APPROACH ? -1 : 1) * years * years;
    }
    @ParameterizedTest @EnumSource(value = EventType.class, names = {"CLOSEST_APPROACH", "FARTHEST_APPROACH", "PERIHELION", "BRIGHTEST_APPROACH"})
    void findsRealExtremaBesideEitherEdgeButNeverReturnsOutsideTheRequestedWindow(EventType type) {
        for (int days : new int[] {1, 10, 20, 44}) {
            for (boolean atStart : List.of(true, false)) {
                var repository = new InMemoryEventCatalogRepository();
                var service = service(type, EDGE, repository);
                Instant from = atStart ? EDGE.minus(Duration.ofDays(days)) : EDGE.minus(Duration.ofDays(25 * 365L));
                Instant to = atStart ? EDGE.plus(Duration.ofDays(25 * 365L)) : EDGE.plus(Duration.ofDays(days));
                var result = service.generate(type, BodyId.EARTH, BodyId.HALLEY, from, to);
                assertThat(result).as("%s days=%s atStart=%s", type, days, atStart).hasSize(1);
                assertThat(Duration.between(EDGE, result.getFirst().displayTimeUtc()).abs()).isLessThanOrEqualTo(Duration.ofHours(6));
                assertThat(result.getFirst().displayTimeUtc()).isBetween(from, to);
            }
        }
        for (boolean before : List.of(true, false)) {
            Instant from = before ? EDGE.plusSeconds(86400) : EDGE.minus(Duration.ofDays(25 * 365L));
            Instant to = before ? EDGE.plus(Duration.ofDays(25 * 365L)) : EDGE.minusSeconds(86400);
            var repository = new InMemoryEventCatalogRepository();
            assertThat(service(type, EDGE, repository).generate(type, BodyId.EARTH, BodyId.HALLEY, from, to)).isEmpty();
            assertThat(repository.search(new CatalogQuery(type, BodyId.EARTH, BodyId.HALLEY,
                from.minus(Duration.ofDays(200)), to.plus(Duration.ofDays(200)), false, false, 100, null)).events()).isEmpty();
        }
    }
    @Test void dailyExtremaAreFoundWithinTwelveHoursOfEitherWindowEdge() {
        for (EventType type : List.of(EventType.GREATEST_ELONGATION, EventType.TRANSIT)) {
            var provider = mock(CompositeEphemerisProvider.class);
            when(provider.sourceFor(any())).thenReturn("SYNTHETIC_DAILY_EDGE");
            when(provider.heliocentricPosition(any(), any())).thenAnswer(call -> {
                BodyId body = call.getArgument(0); Instant time = call.getArgument(1);
                if (body == BodyId.EARTH) return new Vector3Au(1, 0, 0);
                double days = Duration.between(EDGE, time).toSeconds() / 86400.0;
                double y = type == EventType.TRANSIT ? 0.0001 + 0.01 * days * days : 0.2 / (1 + days * days);
                return new Vector3Au(0.5, y, 0);
            });
            for (boolean atStart : List.of(true, false)) {
                Instant from = EDGE.minusSeconds(atStart ? 3600 : 10 * 86400);
                Instant to = EDGE.plusSeconds(atStart ? 10 * 86400 : 3600);
                var service = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
                assertThat(service.generate(type, BodyId.EARTH, BodyId.MERCURY, from, to))
                    .as("%s atStart=%s", type, atStart).hasSize(1)
                    .allSatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(EDGE));
            }
        }
    }

    @Test void unalignedWindowsNeedTwoCoarseSamplesPastTheEnd() {
        // The physical minimum is after half of the final grid cell, so its nearest
        // sample is after 'to' and that sample needs one more neighbour to bracket it.
        for (EventType type : List.of(EventType.CLOSEST_APPROACH, EventType.PERIHELION)) {
            long stepDays = type == EventType.PERIHELION ? 90 : 30;
            long step = stepDays * 86400;
            Instant grid = Instant.ofEpochSecond(Math.floorDiv(EDGE.getEpochSecond(), step) * step);
            Instant peak = grid.plusSeconds(step * 3 / 4), to = peak.plusSeconds(3600);
            Instant from = to.minus(Duration.ofDays(type == EventType.PERIHELION ? 9151 : 9141));
            var service = service(type, peak, new InMemoryEventCatalogRepository());
            assertThat(service.generate(type, BodyId.EARTH, BodyId.HALLEY, from, to))
                .as("%s unaligned end", type).hasSize(1)
                .allSatisfy(event -> assertThat(Duration.between(peak, event.computedTimeUtc()).abs())
                    .isLessThanOrEqualTo(Duration.ofHours(6)));
        }
    }

    @Test void refinementNeverEscapesItsPaddedBracketEvenWhenTheTrueMinimumIsOutside() {
        var service = service(EventType.CLOSEST_APPROACH, EDGE, new InMemoryEventCatalogRepository());
        Instant from = EDGE.minus(Duration.ofDays(1)), to = EDGE.minusSeconds(1000);
        Object refined = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refineDistanceExtremum",
            BodyId.EARTH, BodyId.HALLEY, from, to, Duration.ofHours(1), true);
        Instant time = (Instant)org.springframework.test.util.ReflectionTestUtils.getField(refined, "time");
        assertThat(time).isBetween(from, to);
    }


    @Test void perihelionFiveDaysAfterGridNeedsThePrecedingCoarseSample() {
        long step = 90 * 86400L;
        Instant grid = Instant.ofEpochSecond(Math.floorDiv(EDGE.getEpochSecond(), step) * step);
        Instant peak = grid.plus(Duration.ofDays(10));
        var service = service(EventType.PERIHELION, peak, new InMemoryEventCatalogRepository());
        assertThat(service.generate(EventType.PERIHELION, BodyId.EARTH, BodyId.HALLEY,
            grid.plus(Duration.ofDays(5)), grid.plus(Duration.ofDays(25 * 365L))))
            .hasSize(1).allSatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(peak));
    }

    @Test void morningElongationNeedsThePreviousMidnightNeighbour() {
        Instant peak = Instant.parse("2026-02-19T06:00:00Z");
        var provider = mock(CompositeEphemerisProvider.class);
        when(provider.sourceFor(any())).thenReturn("SYNTHETIC_MORNING");
        when(provider.heliocentricPosition(any(), any())).thenAnswer(call -> {
            if (call.getArgument(0) == BodyId.EARTH) return new Vector3Au(1, 0, 0);
            Instant time = call.getArgument(1);
            double days = Duration.between(peak, time).toSeconds() / 86400.0;
            return new Vector3Au(0.5, 0.2 / (1 + days * days), 0);
        });
        var service = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
        assertThat(service.generate(EventType.GREATEST_ELONGATION, BodyId.EARTH, BodyId.MERCURY,
            peak.minusSeconds(3 * 3600), peak.plus(Duration.ofDays(30))))
            .hasSize(1).allSatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(peak));
    }

    @Test void grazingMercuryTransitsRemainReachableOnAFiveMinuteGrid() {
        for (String date : List.of("2039-11-07", "1999-11-15")) {
            Instant day = Instant.parse(date + "T00:00:00Z");
            var provider = RecordedOrbitFixtures.provider();
            var service = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
            var found = service.generate(EventType.TRANSIT, BodyId.EARTH, BodyId.MERCURY,
                day.minus(Duration.ofDays(300)), day.plus(Duration.ofDays(300))).stream()
                .filter(event -> !event.computedTimeUtc().isBefore(day) && event.computedTimeUtc().isBefore(day.plus(Duration.ofDays(1))))
                .findFirst().orElseThrow();
            // Independent minute sweep also guards temporal resolution, not just disk overlap.
            Instant expected = day; double separation = Double.POSITIVE_INFINITY;
            for (int minute = 0; minute < 1440; minute++) {
                Instant time = day.plusSeconds(minute * 60L);
                var earth = provider.heliocentricPosition(BodyId.EARTH, time);
                double angle = provider.heliocentricPosition(BodyId.MERCURY, time).minus(earth).angleBetweenDeg(earth.negate());
                if (angle < separation) { separation = angle; expected = time; }
            }
            assertThat(Duration.between(expected, found.computedTimeUtc()).abs()).isLessThanOrEqualTo(Duration.ofMinutes(3));
        }
    }

    @Test void regenerationKeepsUnavailableRecordsVisible() {
        var repository = new InMemoryEventCatalogRepository();
        var service = service(EventType.CLOSEST_APPROACH, EDGE, repository);
        Instant from = EDGE.minus(Duration.ofDays(100)), to = EDGE.plus(Duration.ofDays(100));
        var candidate = service.generate(EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.HALLEY, from, to).getFirst();
        var uncovered = candidate.unavailable("JPL coverage unavailable");
        repository.upsert(uncovered);
        assertThat(service.generate(EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.HALLEY, from, to)).containsExactly(uncovered);
    }
}

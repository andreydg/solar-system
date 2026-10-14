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
    @ParameterizedTest @EnumSource(value = EventType.class, names = {"CLOSEST_APPROACH", "FARTHEST_APPROACH", "PERIHELION"})
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
    @Test void refinementNeverEscapesItsPaddedBracketEvenWhenTheTrueMinimumIsOutside() {
        var service = service(EventType.CLOSEST_APPROACH, EDGE, new InMemoryEventCatalogRepository());
        Instant from = EDGE.minus(Duration.ofDays(1)), to = EDGE.minusSeconds(1000);
        Object refined = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refineDistanceExtremum",
            BodyId.EARTH, BodyId.HALLEY, from, to, Duration.ofHours(1), true);
        Instant time = (Instant)org.springframework.test.util.ReflectionTestUtils.getField(refined, "time");
        assertThat(time).isBetween(from, to);
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

package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.*;
import dev.andreydg.solarsystem.ephemeris.RecordedOrbitFixtures;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RefinementBracketTests {
    private static final dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider PROVIDER = RecordedOrbitFixtures.provider();
    private static List<CatalogEvent> generate(EventType type, BodyId target, Instant from, Instant to) {
        return new EventCatalogService(PROVIDER, new InMemoryEventCatalogRepository())
            .generate(type, BodyId.EARTH, target, from, to);
    }


    @Test void refinementCannotJumpAcrossTheCandidateNeighbours() {
        for (var type : List.of(EventType.CLOSEST_APPROACH, EventType.BRIGHTEST_APPROACH)) {
            long days = type == EventType.CLOSEST_APPROACH ? 30 : 5;
            long step = days * 86400;
            Instant peak = Instant.ofEpochSecond(Math.floorDiv(Instant.parse("2027-01-01T00:00:00Z").getEpochSecond(), step) * step);
            var provider = org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider.class);
            org.mockito.Mockito.when(provider.sourceFor(org.mockito.ArgumentMatchers.any())).thenReturn("NEIGHBOUR_BRACKET");
            org.mockito.Mockito.when(provider.heliocentricPosition(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    if (call.getArgument(0) == BodyId.EARTH) return new dev.andreydg.solarsystem.ephemeris.Vector3Au(1, 0, 0);
                    double offset = Duration.between(peak, call.getArgument(1)).toSeconds() / (double)step;
                    double distance = offset <= 1 ? 1 + offset * offset : Math.max(0.1, 2 - 2 * (offset - 1));
                    return new dev.andreydg.solarsystem.ephemeris.Vector3Au(1 + distance, 0, 0);
                });
            var result = new EventCatalogService(provider, new InMemoryEventCatalogRepository()).generate(type,
                BodyId.EARTH, BodyId.MARS, peak.minus(Duration.ofDays(10)), peak.plus(Duration.ofDays(25 * 365L)));
            assertThat(result).as(type.name()).anySatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(peak));
        }
    }

    @Test void flatGeometryCannotPublishBracketEdgesAsExtrema() {
        var provider = org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider.class);
        org.mockito.Mockito.when(provider.sourceFor(org.mockito.ArgumentMatchers.any())).thenReturn("FLAT_GEOMETRY");
        org.mockito.Mockito.when(provider.heliocentricPosition(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> call.getArgument(0) == BodyId.EARTH
                ? new dev.andreydg.solarsystem.ephemeris.Vector3Au(1, 0, 0)
                : new dev.andreydg.solarsystem.ephemeris.Vector3Au(0.5, 0.1, 0));
        for (var type : List.of(EventType.CLOSEST_APPROACH, EventType.BRIGHTEST_APPROACH)) {
            var service = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
            assertThat(service.generate(type, BodyId.EARTH, BodyId.VENUS,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))).isEmpty();
        }
    }

    @Test void longWindowsFindRealEnckeAndVenusExtremaNotBracketEdges() {
        var encke = generate(EventType.CLOSEST_APPROACH, BodyId.ENCKE,
            Instant.parse("2115-01-01T00:00:00Z"), Instant.parse("2140-01-01T00:00:00Z"));
        assertThat(encke).anySatisfy(event -> assertThat(Duration.between(
            Instant.parse("2115-11-16T15:54:00Z"), event.computedTimeUtc()).abs()).isLessThan(Duration.ofHours(1)));
        assertThat(encke).noneSatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(Instant.parse("2116-01-16T00:00:00Z")));
        var venus = generate(EventType.BRIGHTEST_APPROACH, BodyId.VENUS,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2051-01-01T00:00:00Z"));
        for (String day : List.of("2026-09-25", "2033-04-19"))
            assertThat(venus).anySatisfy(event -> assertThat(event.computedTimeUtc().toString()).startsWith(day));
        assertThat(venus).noneSatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(Instant.parse("2033-03-01T00:00:00Z")));
    }

    @Test void thousandNextPrevWindowsMatchShortWindowExtremaWithoutEdgeArtifacts() {
        for (var target : List.of(BodyId.VENUS, BodyId.ENCKE)) {
            var type = target == BodyId.VENUS ? EventType.BRIGHTEST_APPROACH : EventType.CLOSEST_APPROACH;
            var reference = new TreeSet<Instant>();
            // Short, overlapping windows provide independent brackets for the 25-year searches.
            for (int year = 1994; year < 2162; year += 2) {
                Instant from = Instant.parse(year + "-01-01T00:00:00Z");
                for (var event : generate(type, target, from, from.plus(Duration.ofDays(3 * 365L))))
                    reference.add(event.computedTimeUtc());
            }
            for (int i = 0; i < 500; i++) {
                Instant anchor = Instant.parse("2020-01-01T00:00:00Z")
                    .plusSeconds((long)i * 110 * 365 * 86400 / 499 + i * 77L);
                boolean next = (i & 1) == 0;
                Instant from = next ? anchor : anchor.minus(Duration.ofDays(25 * 365L));
                Instant to = next ? anchor.plus(Duration.ofDays(25 * 365L)) : anchor;
                var actual = generate(type, target, from, to).stream().map(CatalogEvent::computedTimeUtc).sorted().toList();
                Instant expected = next ? reference.ceiling(anchor) : reference.floor(anchor);
                assertThat(actual).as("%s anchor=%s next=%s", target, anchor, next).isNotEmpty();
                assertThat(next ? actual.getFirst() : actual.getLast()).as("%s anchor=%s next=%s", target, anchor, next).isEqualTo(expected);
            }
        }
    }
}

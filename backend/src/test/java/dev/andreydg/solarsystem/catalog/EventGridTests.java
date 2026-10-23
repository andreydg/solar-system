package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.*;
import dev.andreydg.solarsystem.ephemeris.RecordedOrbitFixtures;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EventGridTests {
    @Test void realUnaligned9141And9151DayWindowsKeepTheirFinalExtremum() {
        var provider = RecordedOrbitFixtures.provider();
        Object[][] cases = {
            {EventType.FARTHEST_APPROACH, BodyId.SATURN, BodyId.URANUS, "2056-02-20T10:00:00Z", 9141L},
            {EventType.PERIHELION, BodyId.EARTH, BodyId.HALLEY, "2061-07-28T12:00:00Z", 9151L}
        };
        for (Object[] c : cases) {
            Instant near = Instant.parse((String)c[3]);
            var service = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
            Instant expected = service.generate((EventType)c[0], (BodyId)c[1], (BodyId)c[2],
                near.minus(Duration.ofDays(200)), near.plus(Duration.ofDays(200))).getFirst().computedTimeUtc();
            Instant to = expected.plusSeconds(3600), from = to.minus(Duration.ofDays((long)c[4]));
            assertThat(service.generate((EventType)c[0], (BodyId)c[1], (BodyId)c[2], from, to))
                .as(Arrays.toString(c)).hasSize(1).allSatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(expected));
        }
    }

    @Test void realBrightestElongationAndTransitEventsRemainReachableFromBothSides() {
        Object[][] cases = {
            {EventType.BRIGHTEST_APPROACH, BodyId.MARS, "2027-02-19T12:00:00Z"},
            {EventType.BRIGHTEST_APPROACH, BodyId.JUPITER, "2026-01-10T00:00:00Z"},
            {EventType.GREATEST_ELONGATION, BodyId.MERCURY, "2026-02-19T12:00:00Z"},
            {EventType.TRANSIT, BodyId.MERCURY, "2032-11-13T12:00:00Z"}
        };
        var provider = RecordedOrbitFixtures.provider();
        for (Object[] c : cases) {
            Instant near = Instant.parse((String)c[2]);
            var baseline = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
            Instant expected = baseline.generate((EventType)c[0], BodyId.EARTH, (BodyId)c[1],
                near.minus(Duration.ofDays(50)), near.plus(Duration.ofDays(50))).stream()
                .min(Comparator.comparingLong(e -> Duration.between(near, e.computedTimeUtc()).abs().toSeconds()))
                .orElseThrow().computedTimeUtc();
            for (int hours : new int[] {1, 12, 24, 14 * 24, 26 * 24}) for (boolean next : List.of(false, true)) {
                Instant from = expected.minus(Duration.ofHours(next ? hours : 25 * 365 * 24));
                Instant to = expected.plus(Duration.ofHours(next ? 25 * 365 * 24 : hours));
                var service = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
                assertThat(service.generate((EventType)c[0], BodyId.EARTH, (BodyId)c[1], from, to))
                    .as("%s hours=%s next=%s", Arrays.toString(c), hours, next)
                    .anySatisfy(event -> assertThat(event.computedTimeUtc()).isEqualTo(expected));
            }
        }
    }


    @Test void motionAndTransitCandidateScansStayOnTheAbsoluteDailyGrid() {
        Instant from = Instant.parse("2026-09-01T01:17:00Z");
        for (var type : List.of(EventType.STATIONARY, EventType.RETROGRADE_START, EventType.RETROGRADE_END, EventType.TRANSIT)) {
            var provider = org.mockito.Mockito.spy(RecordedOrbitFixtures.provider());
            new EventCatalogService(provider, new InMemoryEventCatalogRepository()).generate(type, BodyId.EARTH,
                type == EventType.TRANSIT ? BodyId.MERCURY : BodyId.MARS, from, from.plus(Duration.ofDays(365)));
            var first = org.mockito.Mockito.mockingDetails(provider).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("heliocentricPosition")).findFirst().orElseThrow();
            assertThat((Instant)first.getArgument(1)).isEqualTo(Instant.parse("2026-08-31T00:00:00Z"));
        }
    }

    @Test void overlappingUnalignedWindowsProduceOneTimeIdAndJobPerPhysicalEvent() {
        Object[][] cases = {
            // Exterior-ramp model anchor; RecordedEventAcceptanceTests independently
            // refines it against Horizons to 2056-03-07T10:20Z.
            {EventType.FARTHEST_APPROACH, BodyId.SATURN, BodyId.URANUS, "2056-03-01T21:00:00Z"},
            {EventType.FARTHEST_APPROACH, BodyId.URANUS, BodyId.NEPTUNE, "2084-04-06T18:00:00Z"},
            {EventType.CLOSEST_APPROACH, BodyId.SATURN, BodyId.NEPTUNE, "2025-09-11T12:00:00Z"},
            {EventType.BRIGHTEST_APPROACH, BodyId.EARTH, BodyId.MARS, "2027-02-19T12:00:00Z"},
            {EventType.PERIHELION, BodyId.EARTH, BodyId.HALLEY, "2061-07-28T12:00:00Z"},
            {EventType.PERIHELION, BodyId.EARTH, BodyId.ENCKE, "2027-02-09T00:00:00Z"},
            {EventType.STATIONARY, BodyId.EARTH, BodyId.MARS, "2027-01-10T12:47:13Z"},
            {EventType.RETROGRADE_START, BodyId.EARTH, BodyId.MARS, "2027-01-10T12:47:13Z"},
            {EventType.RETROGRADE_END, BodyId.EARTH, BodyId.MARS, "2027-04-01T14:04:28Z"},
            {EventType.TRANSIT, BodyId.EARTH, BodyId.MERCURY, "2032-11-13T08:50:00Z"}
        };
        var provider = RecordedOrbitFixtures.provider();
        for (Object[] c : cases) {
            Instant expected = Instant.parse((String)c[3]);
            var times = new HashSet<Instant>(); var ids = new HashSet<String>();
            var repository = new InMemoryEventCatalogRepository();
            var shared = new EventCatalogService(provider, repository);
            for (int hour = 0; hour < 24; hour++) {
                Instant from = expected.minus(Duration.ofDays(100)).plusSeconds(hour * 3600 + hour * 17 * 60);
                Instant to = from.plus(Duration.ofDays(25 * 365));
                // A fresh repository prevents old stored values masking an unstable new estimate.
                var fresh = new EventCatalogService(provider, new InMemoryEventCatalogRepository());
                var candidate = fresh.generate((EventType)c[0], (BodyId)c[1], (BodyId)c[2], from, to).stream()
                    .min(Comparator.comparingLong(e -> Duration.between(expected, e.computedTimeUtc()).abs().toSeconds()))
                    .orElseThrow();
                assertThat(Duration.between(expected, candidate.computedTimeUtc()).abs()).as(Arrays.toString(c))
                    .isLessThan(Duration.ofDays(10));
                times.add(candidate.computedTimeUtc()); ids.add(candidate.id());
                shared.generate((EventType)c[0], (BodyId)c[1], (BodyId)c[2], from, to);
            }
            assertThat(times).as(Arrays.toString(c)).hasSize(1);
            assertThat(ids).hasSize(1);
            var time = times.iterator().next();
            assertThat(repository.search(new CatalogQuery((EventType)c[0], (BodyId)c[1], (BodyId)c[2],
                time.minusSeconds(86400), time.plusSeconds(86400), false, false, 100, null)).events()).hasSize(1);
            int jobsForEvent = 0;
            for (var lease = repository.claim(Instant.now(), Duration.ofMinutes(10)); lease.isPresent();
                lease = repository.claim(Instant.now(), Duration.ofMinutes(10))) {
                var job = lease.orElseThrow();
                if (ids.contains(job.eventId())) jobsForEvent++;
                repository.abandon(job, "test consumed job", Instant.now());
            }
            assertThat(jobsForEvent).isEqualTo(1);
        }
    }
}

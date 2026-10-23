package dev.andreydg.solarsystem.jpl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Actual recorded Horizons vectors, not a duplicate implementation or live test dependency. */
class RecordedEventAcceptanceTests {
    private JplHorizonsClient fixtureClient;
    record Case(EventType type, String fixture, BodyId a, BodyId b, String anchor, String expected) {}
    static Stream<Case> accepted() {
        return Stream.of(
            new Case(EventType.CLOSEST_APPROACH, "mars-near", BodyId.EARTH, BodyId.MARS, "2027-02-20T00:00:00Z", "2027-02-20T00:15:00Z"),
            new Case(EventType.OPPOSITION, "mars-near", BodyId.EARTH, BodyId.MARS, "2027-02-20T00:00:00Z", "2027-02-19T15:43:10Z"),
            new Case(EventType.BRIGHTEST_APPROACH, "mars-near", BodyId.EARTH, BodyId.MARS, "2027-02-20T00:00:00Z", "2027-02-19T16:25:00Z"),
            new Case(EventType.FARTHEST_APPROACH, "mars-far", BodyId.EARTH, BodyId.MARS, "2028-05-12T00:00:00Z", "2028-05-11T21:30:00Z"),
            new Case(EventType.CONJUNCTION, "mars-conjunction", BodyId.EARTH, BodyId.MARS, "2028-03-22T00:00:00Z", "2028-03-21T03:09:08Z"),
            new Case(EventType.RETROGRADE_START, "mars-start", BodyId.EARTH, BodyId.MARS, "2027-01-11T00:00:00Z", "2027-01-10T12:47:13Z"),
            new Case(EventType.STATIONARY, "mars-start", BodyId.EARTH, BodyId.MARS, "2027-01-11T00:00:00Z", "2027-01-10T12:47:13Z"),
            new Case(EventType.RETROGRADE_END, "mars-end", BodyId.EARTH, BodyId.MARS, "2027-04-02T00:00:00Z", "2027-04-01T14:04:28Z"),
            new Case(EventType.GREATEST_ELONGATION, "mercury-elongation", BodyId.EARTH, BodyId.MERCURY, "2026-02-20T00:00:00Z", "2026-02-19T17:30:00Z"),
            new Case(EventType.TRANSIT, "mercury-transit", BodyId.EARTH, BodyId.MERCURY, "2032-11-13T00:00:00Z", "2032-11-13T08:50:00Z"),
            new Case(EventType.PERIHELION, "ceres-perihelion", BodyId.EARTH, BodyId.CERES, "2027-07-11T00:00:00Z", "2027-07-11T04:15:00Z"),
            new Case(EventType.FARTHEST_APPROACH, "saturn-uranus", BodyId.SATURN, BodyId.URANUS, "2056-02-20T10:00:00Z", "2056-03-07T10:20:00Z"),
            new Case(EventType.FARTHEST_APPROACH, "uranus-neptune", BodyId.URANUS, BodyId.NEPTUNE, "2084-04-06T18:00:00Z", "2084-03-06T05:20:00Z")
        );
    }
    static Stream<Case> rejected() {
        return Stream.of(
            new Case(EventType.CLOSEST_APPROACH, "mars-far", BodyId.EARTH, BodyId.MARS, "2028-05-12T00:00:00Z", ""),
            new Case(EventType.FARTHEST_APPROACH, "mars-near", BodyId.EARTH, BodyId.MARS, "2027-02-20T00:00:00Z", ""),
            new Case(EventType.OPPOSITION, "mars-conjunction", BodyId.EARTH, BodyId.MARS, "2028-03-22T00:00:00Z", ""),
            new Case(EventType.CONJUNCTION, "mars-near", BodyId.EARTH, BodyId.MARS, "2027-02-20T00:00:00Z", ""),
            new Case(EventType.GREATEST_ELONGATION, "mercury-transit", BodyId.EARTH, BodyId.MERCURY, "2032-11-13T00:00:00Z", ""),
            new Case(EventType.TRANSIT, "mercury-elongation", BodyId.EARTH, BodyId.MERCURY, "2026-02-20T00:00:00Z", ""),
            new Case(EventType.RETROGRADE_START, "mars-end", BodyId.EARTH, BodyId.MARS, "2027-04-02T00:00:00Z", ""),
            new Case(EventType.RETROGRADE_END, "mars-start", BodyId.EARTH, BodyId.MARS, "2027-01-11T00:00:00Z", ""),
            new Case(EventType.STATIONARY, "mars-near", BodyId.EARTH, BodyId.MARS, "2027-02-20T00:00:00Z", ""),
            new Case(EventType.BRIGHTEST_APPROACH, "mars-far", BodyId.EARTH, BodyId.MARS, "2028-05-12T00:00:00Z", ""),
            new Case(EventType.PERIHELION, "ceres-aphelion", BodyId.EARTH, BodyId.CERES, "2029-10-26T00:00:00Z", "")
        );
    }
    @ParameterizedTest @MethodSource("accepted")
    void acceptsAllElevenTypesAndSlowOuterPairsWithCorrectProvenance(Case c) throws Exception {
        var refined = new java.util.concurrent.atomic.AtomicReference<EventGeometry.Sample>();
        CatalogEvent result;
        try (var geometry = mockStatic(EventGeometry.class, CALLS_REAL_METHODS)) {
            geometry.when(() -> EventGeometry.locate(any(), anyList(), any(), eq(true))).thenAnswer(call -> {
                var sample = (EventGeometry.Sample) call.callRealMethod();
                refined.set(sample);
                return sample;
            });
            result = validator(c).validateEvent("recorded");
        }
        assertThat(refined.get()).as("The fetched fine grid must actually refine the answer").isNotNull();
        assertThat(result.validatedTimeUtc()).isEqualTo(refined.get().time());
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
        verify(fixtureClient, atLeastOnce()).vectors(any(), any(), any(), eq("5m"));
        var expected = Instant.parse(c.expected());
        assertThat(result.validatedTimeUtc()).isBetween(expected.minusSeconds(1), expected.plusSeconds(1));
        assertThat(result.computedTimeUtc()).isEqualTo(Instant.parse(c.anchor()));
        if (c.fixture().equals("saturn-uranus") || c.fixture().equals("uranus-neptune"))
            assertThat(Math.abs(Duration.between(result.computedTimeUtc(), result.validatedTimeUtc()).toDays())).isGreaterThan(7);
        if (c.type() == EventType.BRIGHTEST_APPROACH) {
            assertThat(result.validatedMagnitude()).isBetween(-1.4, -1.1);
            assertThat(result.validatedSource()).isEqualTo("JPL_GEOMETRY_MODEL_MAGNITUDE");
        } else assertThat(result.validatedSource()).isEqualTo("JPL_HORIZONS");
    }

    @ParameterizedTest @MethodSource("accepted")
    void everyRecordingValidatesTheActualGeneratorAnchor(Case c) throws Exception {
        var catalog = new EventCatalogService(dev.andreydg.solarsystem.ephemeris.RecordedOrbitFixtures.provider(),
            new dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository());
        Instant near = Instant.parse(c.anchor());
        var generated = catalog.generate(c.type(), c.a(), c.b(), near.minus(Duration.ofDays(100)),
            near.plus(Duration.ofDays(25 * 365L))).stream()
            .min(Comparator.comparingLong(event -> Duration.between(near, event.computedTimeUtc()).abs().toSeconds())).orElseThrow();
        if (c.fixture().equals("saturn-uranus"))
            assertThat(generated.computedTimeUtc()).isEqualTo(Instant.parse("2056-03-01T21:00:00Z"));
        validator(c); // Load recorded responses, then validate the real stored event, not a synthetic DTO.
        var result = new JplValidationService(catalog, fixtureClient).validateEvent(generated.id());
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(result.validatedTimeUtc()).isBetween(Instant.parse(c.expected()).minusSeconds(1), Instant.parse(c.expected()).plusSeconds(1));
        if (Set.of(EventType.STATIONARY, EventType.RETROGRADE_START, EventType.RETROGRADE_END).contains(c.type()))
            assertThat(Duration.between(Instant.parse(c.expected()), generated.computedTimeUtc()).abs()).isLessThan(Duration.ofHours(1));
    }

    @org.junit.jupiter.api.Test void validatesTheActualGeneratedUranusNeptuneAnchor() throws Exception {
        var catalog = new EventCatalogService(dev.andreydg.solarsystem.ephemeris.RecordedOrbitFixtures.provider(),
            new dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository());
        var generated = catalog.generate(EventType.FARTHEST_APPROACH, BodyId.URANUS, BodyId.NEPTUNE,
            Instant.parse("2080-01-01T00:00:00Z"), Instant.parse("2090-01-01T00:00:00Z")).getFirst();
        assertThat(generated.computedTimeUtc()).isEqualTo(Instant.parse("2084-04-06T18:00:00Z"));
        var c = new Case(generated.type(), "uranus-neptune", generated.bodyA(), generated.bodyB(),
            generated.computedTimeUtc().toString(), "2084-03-06T05:20:00Z");
        assertThat(validator(c).validateEvent("recorded").validatedTimeUtc()).isEqualTo(Instant.parse(c.expected()));
    }

    @ParameterizedTest @MethodSource("rejected")
    void rejectsRecordedGeometryOfTheWrongKindOrDirection(Case c) throws Exception {
        var validation = validator(c);
        assertThatThrownBy(() -> validation.validateEvent("recorded"))
            .isInstanceOf(JplHorizonsException.class).hasMessageContaining("bracket");
    }
    private JplValidationService validator(Case c) throws Exception {
        var catalog = mock(EventCatalogService.class);
        // Exercise actual photometry; a default Mockito double would silently make every magnitude zero.
        when(catalog.computeVisualMagnitude(any(), anyDouble(), anyDouble(), anyDouble())).thenCallRealMethod();
        var time = Instant.parse(c.anchor());
        var event = new CatalogEvent("recorded", c.type(), c.a(), c.b(), time, 1.0, 0.0, -1.0, "APPROXIMATE",
            null, null, null, null, null, ValidationStatus.PENDING, time.minus(Duration.ofDays(365)),
            time.plus(Duration.ofDays(365)), time, null, null, null);
        when(catalog.findById("recorded")).thenReturn(Optional.of(event));
        var horizons = mock(JplHorizonsClient.class);
        fixtureClient = horizons;
        var fixtures = new HashMap<String, List<JplVector>>();
        for (BodyId body : List.of(c.a(), c.b())) {
            if (c.type() == EventType.PERIHELION && body == BodyId.EARTH) continue;
            for (String grid : List.of("coarse", "fine")) {
                if (c.fixture().equals("ceres-aphelion") && grid.equals("fine")) continue;
                String name = "/jpl/cases/" + c.fixture() + "-" + body.apiValue() + "-" + grid + ".csv";
                try (var input = Objects.requireNonNull(getClass().getResourceAsStream(name), name)) {
                    fixtures.put(body.apiValue() + grid, new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().map(line -> {
                        var values = line.split(",");
                        return new JplVector(body, Instant.parse(values[0]), new Vector3Au(Double.parseDouble(values[1]),
                            Double.parseDouble(values[2]), Double.parseDouble(values[3])), values[0], line);
                    }).toList());
                }
            }
        }
        when(horizons.vectors(any(), any(), any(), any())).thenAnswer(call -> {
            BodyId body = call.getArgument(0); Instant from = call.getArgument(1); Instant to = call.getArgument(2);
            String grid = "5m".equals(call.getArgument(3)) ? "fine" : "coarse";
            return fixtures.get(body.apiValue() + grid).stream()
                .filter(v -> !v.requestedTimeUtc().isBefore(from) && !v.requestedTimeUtc().isAfter(to)).toList();
        });
        return new JplValidationService(catalog, horizons);
    }
}

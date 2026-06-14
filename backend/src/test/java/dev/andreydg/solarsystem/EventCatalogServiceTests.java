package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventCatalogService;
import dev.andreydg.solarsystem.catalog.EventType;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "solar-system.storage=in-memory",
    "solar-system.jpl.async-validation-enabled=false",
    "solar-system.trajectories.cache-dir=../data/trajectories",
    "solar-system.trajectories.refresh-from-jpl=false"
})
class EventCatalogServiceTests {
    @Autowired
    private EventCatalogService service;

    @Test
    void generatesAndQueriesClosestApproaches() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2028-12-31T00:00:00Z");

        var generated = service.generate(EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS, from, to);
        var queried = service.query(EventType.CLOSEST_APPROACH, BodyId.MARS, BodyId.EARTH, from, to);

        assertThat(generated).isNotEmpty();
        assertThat(queried).hasSameSizeAs(generated);
        assertThat(queried.getFirst().displayDistanceAu()).isPositive();
    }

    @Test
    void generatesAndQueriesFarthestApproaches() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2028-12-31T00:00:00Z");

        var generated = service.generate(EventType.FARTHEST_APPROACH, BodyId.EARTH, BodyId.MARS, from, to);
        var queried = service.query(EventType.FARTHEST_APPROACH, BodyId.MARS, BodyId.EARTH, from, to);

        assertThat(generated).isNotEmpty();
        assertThat(queried).hasSameSizeAs(generated);
        assertThat(queried.getFirst().displayDistanceAu()).isPositive();
    }

    @Test
    void generatesGreatestElongationForMercury() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2027-12-31T00:00:00Z");

        var generated = service.generate(EventType.GREATEST_ELONGATION, BodyId.EARTH, BodyId.MERCURY, from, to);

        assertThat(generated).isNotEmpty();
        assertThat(generated.getFirst().displayAngleDeg()).isPositive();
    }

    @Test
    void doesNotGenerateFalseVenusTransitsBefore2117() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2116-12-31T00:00:00Z");

        var generated = service.generate(EventType.TRANSIT, BodyId.EARTH, BodyId.VENUS, from, to);
        var queried = service.query(EventType.TRANSIT, BodyId.EARTH, BodyId.VENUS, from, to);

        assertThat(generated).isEmpty();
        assertThat(queried).isEmpty();
    }

    @Test
    void generatesRealVenusTransitNear2117() {
        Instant from = Instant.parse("2116-01-01T00:00:00Z");
        Instant to = Instant.parse("2118-12-31T00:00:00Z");

        var generated = service.generate(EventType.TRANSIT, BodyId.EARTH, BodyId.VENUS, from, to);

        assertThat(generated).isNotEmpty();
        assertThat(generated.getFirst().displayAngleDeg()).isLessThanOrEqualTo(0.5);
    }

    @Test
    void listsOnlyValidatedEvents() {
        assertThat(service.listValidatedEvents()).isEmpty();
    }

    @Test
    void rejectsPerihelionForMajorPlanets() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2028-12-31T00:00:00Z");

        assertThatThrownBy(() -> service.generate(EventType.PERIHELION, BodyId.EARTH, BodyId.MARS, from, to))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatesOppositionsWithAngleNear180() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2028-12-31T00:00:00Z");

        var generated = service.generate(EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS, from, to);

        assertThat(generated).isNotEmpty();
        for (var event : generated) {
            assertThat(event.displayAngleDeg()).isNotNull();
            // Angle is normalized to [0, 360), so opposition should be near 180
            double angle = event.displayAngleDeg();
            assertThat(Math.abs(angle - 180.0)).as("Opposition angle should be near 180°, was %f", angle).isLessThan(10.0);
        }
    }

    @Test
    void generatesConjunctionsWithAngleNear0() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2028-12-31T00:00:00Z");

        var generated = service.generate(EventType.CONJUNCTION, BodyId.EARTH, BodyId.MARS, from, to);

        assertThat(generated).isNotEmpty();
        for (var event : generated) {
            assertThat(event.displayAngleDeg()).isNotNull();
            // Angle is normalized to [0, 360), so conjunction near 0° can appear as ~0 or ~360
            double angle = event.displayAngleDeg();
            double distanceFrom0 = Math.min(angle, 360.0 - angle);
            assertThat(distanceFrom0).as("Conjunction angle should be near 0°, was %f", angle).isLessThan(5.0);
        }
    }

    @Test
    void stationaryEventsAreTheUnionOfRetrogradeStartsAndEnds() {
        // STATIONARY uses ANY_ZERO crossing detection, while RETROGRADE_START (prograde->retrograde)
        // and RETROGRADE_END (retrograde->prograde) each catch one direction. Every stationary point
        // is therefore exactly one retrograde start or end, refined to the same instant.
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2031-01-01T00:00:00Z");

        List<Instant> stationary = times(service.generate(EventType.STATIONARY, BodyId.EARTH, BodyId.MARS, from, to));
        List<Instant> retroStart = times(service.generate(EventType.RETROGRADE_START, BodyId.EARTH, BodyId.MARS, from, to));
        List<Instant> retroEnd = times(service.generate(EventType.RETROGRADE_END, BodyId.EARTH, BodyId.MARS, from, to));

        assertThat(retroStart).isNotEmpty();
        assertThat(retroEnd).isNotEmpty();
        // Starts and ends describe distinct turning points.
        assertThat(retroStart).doesNotContainAnyElementsOf(retroEnd);
        // A retrograde loop has one start and one end; over a window they balance to within one clip.
        assertThat(Math.abs(retroStart.size() - retroEnd.size())).isLessThanOrEqualTo(1);

        List<Instant> merged = Stream.concat(retroStart.stream(), retroEnd.stream()).sorted().toList();
        assertThat(stationary).containsExactlyElementsOf(merged);
    }

    @Test
    void retrogradeLoopsStartBeforeTheyEnd() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2031-01-01T00:00:00Z");

        List<Instant> retroStart = times(service.generate(EventType.RETROGRADE_START, BodyId.EARTH, BodyId.MARS, from, to));
        List<Instant> retroEnd = times(service.generate(EventType.RETROGRADE_END, BodyId.EARTH, BodyId.MARS, from, to));

        // Each retrograde period opens with a start that precedes the matching end.
        int loops = Math.min(retroStart.size(), retroEnd.size());
        for (int i = 0; i < loops; i++) {
            assertThat(retroStart.get(i))
                .as("retrograde start #%d should precede its end", i)
                .isBefore(retroEnd.get(i));
        }
    }

    private static List<Instant> times(List<CatalogEvent> events) {
        return events.stream().map(CatalogEvent::displayTimeUtc).sorted().toList();
    }
}

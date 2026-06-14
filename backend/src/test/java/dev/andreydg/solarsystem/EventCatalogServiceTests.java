package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.EventCatalogService;
import dev.andreydg.solarsystem.catalog.EventType;
import java.time.Instant;
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
}

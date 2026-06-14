package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.EventCatalogService;
import dev.andreydg.solarsystem.catalog.EventPairing;
import dev.andreydg.solarsystem.catalog.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "solar-system.storage=in-memory",
    "solar-system.jpl.async-validation-enabled=false",
    "solar-system.trajectories.cache-dir=../data/trajectories",
    "solar-system.trajectories.refresh-from-jpl=false"
})
class EventCatalogCombinationTests {
    private static final Instant SHORT_RANGE_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SHORT_RANGE_TO = Instant.parse("2027-12-31T00:00:00Z");

    @Autowired
    private EventCatalogService service;

    static Stream<Arguments> allValidCombinations() {
        return Stream.of(EventType.values())
            .flatMap(type -> Stream.of(BodyId.values())
                .flatMap(bodyA -> Stream.of(BodyId.values())
                    .filter(bodyB -> EventPairing.isValid(type, bodyA, bodyB))
                    .map(bodyB -> Arguments.of(type, bodyA, bodyB))));
    }

    static Stream<Arguments> sampleInvalidCombinations() {
        return Stream.of(
            Arguments.of(EventType.PERIHELION, BodyId.EARTH, BodyId.MARS),
            Arguments.of(EventType.PERIHELION, BodyId.MARS, BodyId.EARTH),
            Arguments.of(EventType.GREATEST_ELONGATION, BodyId.EARTH, BodyId.MARS),
            Arguments.of(EventType.GREATEST_ELONGATION, BodyId.MARS, BodyId.JUPITER),
            Arguments.of(EventType.TRANSIT, BodyId.EARTH, BodyId.JUPITER),
            Arguments.of(EventType.OPPOSITION, BodyId.MARS, BodyId.JUPITER),
            Arguments.of(EventType.OPPOSITION, BodyId.EARTH, BodyId.MERCURY),
            Arguments.of(EventType.OPPOSITION, BodyId.VENUS, BodyId.EARTH),
            Arguments.of(EventType.CONJUNCTION, BodyId.MERCURY, BodyId.VENUS),
            Arguments.of(EventType.STATIONARY, BodyId.SATURN, BodyId.URANUS),
            Arguments.of(EventType.RETROGRADE_START, BodyId.NEPTUNE, BodyId.JUPITER),
            Arguments.of(EventType.RETROGRADE_END, BodyId.CERES, BodyId.VESTA),
            Arguments.of(EventType.BRIGHTEST_APPROACH, BodyId.EARTH, BodyId.EARTH)
        );
    }

    @ParameterizedTest(name = "{0} {1}+{2}")
    @MethodSource("allValidCombinations")
    void generatesEventsForAllValidCombinations(EventType type, BodyId bodyA, BodyId bodyB) {
        assertTimeout(Duration.ofSeconds(15), () -> {
            assertThatCode(() -> service.generate(type, bodyA, bodyB, SHORT_RANGE_FROM, SHORT_RANGE_TO))
                .doesNotThrowAnyException();
        });
    }

    @ParameterizedTest(name = "{0} {1}+{2}")
    @MethodSource("sampleInvalidCombinations")
    void rejectsInvalidCombinations(EventType type, BodyId bodyA, BodyId bodyB) {
        assertThatThrownBy(() -> service.generate(type, bodyA, bodyB, SHORT_RANGE_FROM, SHORT_RANGE_TO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatesClosestApproachForEarthAndEnckeFrom2036WithinReasonableTime() {
        Instant from = Instant.parse("2036-03-02T20:35:00Z");
        Instant to = Instant.parse("2066-03-02T20:35:00Z");

        var generated = assertTimeout(
            Duration.ofSeconds(30),
            () -> service.generate(EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.ENCKE, from, to)
        );

        assertThat(generated).isNotEmpty();
        assertThat(generated.getFirst().displayDistanceAu()).isPositive();
    }

    @Test
    void generatesPerihelionForEachSmallBody() {
        for (BodyId smallBody : BodyId.values()) {
            if (!smallBody.isSmallBody()) {
                continue;
            }

            var generated = service.generate(
                EventType.PERIHELION,
                BodyId.EARTH,
                smallBody,
                SHORT_RANGE_FROM,
                SHORT_RANGE_TO
            );

            assertThat(generated).isNotEmpty();
            assertThat(generated.getFirst().displayDistanceAu()).isPositive();
        }
    }
}

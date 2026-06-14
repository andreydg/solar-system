package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventPairingTests {

    static Stream<Arguments> oppositionPairings() {
        return Stream.of(
            // Valid outer planet oppositions
            Arguments.of(BodyId.EARTH, BodyId.MARS, true),
            Arguments.of(BodyId.MARS, BodyId.EARTH, true),
            Arguments.of(BodyId.EARTH, BodyId.JUPITER, true),
            Arguments.of(BodyId.EARTH, BodyId.SATURN, true),
            // Valid small body oppositions
            Arguments.of(BodyId.EARTH, BodyId.CERES, true),
            Arguments.of(BodyId.VESTA, BodyId.EARTH, true),
            // Invalid inner planet oppositions
            Arguments.of(BodyId.EARTH, BodyId.MERCURY, false),
            Arguments.of(BodyId.MERCURY, BodyId.EARTH, false),
            Arguments.of(BodyId.EARTH, BodyId.VENUS, false),
            Arguments.of(BodyId.VENUS, BodyId.EARTH, false),
            // Invalid no-Earth oppositions
            Arguments.of(BodyId.MARS, BodyId.JUPITER, false),
            Arguments.of(BodyId.CERES, BodyId.VESTA, false),
            // Invalid same body
            Arguments.of(BodyId.EARTH, BodyId.EARTH, false)
        );
    }

    @ParameterizedTest(name = "OPPOSITION with {0} and {1} is valid: {2}")
    @MethodSource("oppositionPairings")
    void validatesOppositionPairings(BodyId bodyA, BodyId bodyB, boolean expectedValid) {
        assertThat(EventPairing.isValid(EventType.OPPOSITION, bodyA, bodyB)).isEqualTo(expectedValid);
    }

    static Stream<Arguments> transitPairings() {
        return Stream.of(
            // Valid inner planet transits
            Arguments.of(BodyId.EARTH, BodyId.MERCURY, true),
            Arguments.of(BodyId.MERCURY, BodyId.EARTH, true),
            Arguments.of(BodyId.EARTH, BodyId.VENUS, true),
            Arguments.of(BodyId.VENUS, BodyId.EARTH, true),
            // Invalid outer planet transits
            Arguments.of(BodyId.EARTH, BodyId.MARS, false),
            Arguments.of(BodyId.EARTH, BodyId.JUPITER, false),
            // Invalid small body transits
            Arguments.of(BodyId.EARTH, BodyId.CERES, false),
            // Invalid no-Earth transits
            Arguments.of(BodyId.MERCURY, BodyId.VENUS, false)
        );
    }

    @ParameterizedTest(name = "TRANSIT with {0} and {1} is valid: {2}")
    @MethodSource("transitPairings")
    void validatesTransitPairings(BodyId bodyA, BodyId bodyB, boolean expectedValid) {
        assertThat(EventPairing.isValid(EventType.TRANSIT, bodyA, bodyB)).isEqualTo(expectedValid);
    }

    static Stream<Arguments> perihelionPairings() {
        return Stream.of(
            // Valid small body perihelions
            Arguments.of(BodyId.EARTH, BodyId.CERES, true),
            Arguments.of(BodyId.VESTA, BodyId.EARTH, true),
            Arguments.of(BodyId.EARTH, BodyId.HALLEY, true),
            Arguments.of(BodyId.ENCKE, BodyId.EARTH, true),
            // Invalid planet perihelions
            Arguments.of(BodyId.EARTH, BodyId.MARS, false),
            Arguments.of(BodyId.EARTH, BodyId.MERCURY, false),
            // Invalid no-Earth perihelions
            Arguments.of(BodyId.CERES, BodyId.VESTA, false)
        );
    }

    @ParameterizedTest(name = "PERIHELION with {0} and {1} is valid: {2}")
    @MethodSource("perihelionPairings")
    void validatesPerihelionPairings(BodyId bodyA, BodyId bodyB, boolean expectedValid) {
        assertThat(EventPairing.isValid(EventType.PERIHELION, bodyA, bodyB)).isEqualTo(expectedValid);
    }
}

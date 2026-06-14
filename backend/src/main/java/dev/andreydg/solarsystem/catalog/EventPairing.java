package dev.andreydg.solarsystem.catalog;

public final class EventPairing {
    private EventPairing() {
    }

    public static boolean isValid(EventType type, BodyId bodyA, BodyId bodyB) {
        if (bodyA == bodyB) {
            return false;
        }

        return switch (type) {
            case CLOSEST_APPROACH, FARTHEST_APPROACH -> true;
            case GREATEST_ELONGATION, TRANSIT -> isEarthInnerPair(bodyA, bodyB);
            case PERIHELION -> isEarthSmallBodyPair(bodyA, bodyB);
            case OPPOSITION, CONJUNCTION, STATIONARY, RETROGRADE_START, RETROGRADE_END, BRIGHTEST_APPROACH ->
                isEarthObserverPair(bodyA, bodyB);
        };
    }

    private static boolean isEarthObserverPair(BodyId bodyA, BodyId bodyB) {
        return (bodyA == BodyId.EARTH && bodyB != BodyId.EARTH)
            || (bodyB == BodyId.EARTH && bodyA != BodyId.EARTH);
    }

    private static boolean isEarthInnerPair(BodyId bodyA, BodyId bodyB) {
        return (bodyA == BodyId.EARTH && isInnerPlanet(bodyB))
            || (bodyB == BodyId.EARTH && isInnerPlanet(bodyA));
    }

    private static boolean isEarthSmallBodyPair(BodyId bodyA, BodyId bodyB) {
        return (bodyA == BodyId.EARTH && bodyB.isSmallBody())
            || (bodyB == BodyId.EARTH && bodyA.isSmallBody());
    }

    private static boolean isInnerPlanet(BodyId body) {
        return body == BodyId.MERCURY || body == BodyId.VENUS;
    }
}

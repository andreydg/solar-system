package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.CatalogEventsGeneratedEvent;
import dev.andreydg.solarsystem.catalog.EventCatalogService;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class JplValidationService {
    private final EventCatalogService catalogService;
    private final JplHorizonsClient horizonsClient;
    private final JplProperties properties;

    public JplValidationService(
        EventCatalogService catalogService,
        JplHorizonsClient horizonsClient,
        JplProperties properties
    ) {
        this.catalogService = catalogService;
        this.horizonsClient = horizonsClient;
        this.properties = properties;
    }

    public JplVector position(dev.andreydg.solarsystem.catalog.BodyId body, java.time.Instant timeUtc) {
        return horizonsClient.vector(body, timeUtc);
    }

    @Async
    @EventListener
    public void validateGeneratedEvents(CatalogEventsGeneratedEvent generatedEvent) {
        if (!properties.asyncValidationEnabled()) {
            return;
        }

        for (String eventId : generatedEvent.eventIds()) {
            try {
                validateEvent(eventId);
            } catch (RuntimeException exception) {
                catalogService.findById(eventId)
                    .ifPresent(event -> catalogService.storeValidationFailure(
                        event,
                        "JPL validation failed: " + sanitizeFailureMessage(exception)
                    ));
            }
        }
    }

    public CatalogEvent validateEvent(String eventId) {
        CatalogEvent event = catalogService.findById(eventId)
            .orElseThrow(() -> new JplHorizonsException("Catalog event not found: " + eventId));

        return switch (event.type()) {
            case CLOSEST_APPROACH -> validateDistanceExtremum(event, true);
            case FARTHEST_APPROACH -> validateDistanceExtremum(event, false);
            case OPPOSITION, CONJUNCTION -> validateAngularEvent(event);
            case GREATEST_ELONGATION -> validateElongation(event);
            case PERIHELION -> validatePerihelion(event);
            default -> event;
        };
    }

    private CatalogEvent validatePerihelion(CatalogEvent event) {
        if (event.computedDistanceAu() == null) {
            throw new JplHorizonsException("Perihelion event did not include computed heliocentric distance");
        }

        BodyId target = event.bodyA() == BodyId.EARTH ? event.bodyB() : event.bodyA();
        DistanceSample refinedBest = findJplHeliocentricMinimum(
            target,
            event.computedTimeUtc().minus(Duration.ofDays(2)),
            event.computedTimeUtc().plus(Duration.ofDays(2)),
            Duration.ofHours(6)
        );
        DistanceSample refined = findJplHeliocentricMinimum(
            target,
            refinedBest.time().minus(Duration.ofHours(9)),
            refinedBest.time().plus(Duration.ofHours(9)),
            Duration.ofHours(1)
        );

        double deltaKm = Math.abs(refined.distanceAu() - event.computedDistanceAu()) * EventCatalogService.AU_KM;
        String summary = "JPL corrected perihelion %s at %.8f AU; computed %s at %.8f AU".formatted(
            refined.time(),
            refined.distanceAu(),
            event.computedTimeUtc(),
            event.computedDistanceAu()
        );

        return catalogService.storeValidation(
            event,
            refined.time(),
            refined.distanceAu(),
            null,
            null,
            deltaKm,
            summary
        );
    }

    private CatalogEvent validateDistanceExtremum(CatalogEvent event, boolean findMinimum) {
        if (event.computedDistanceAu() == null) {
            throw new JplHorizonsException("Distance event did not include computed distance");
        }

        DistanceSample coarseBest = findJplDistanceExtremum(
            event.bodyA(),
            event.bodyB(),
            event.computedTimeUtc().minus(Duration.ofDays(2)),
            event.computedTimeUtc().plus(Duration.ofDays(2)),
            Duration.ofHours(6),
            findMinimum
        );
        DistanceSample refinedBest = findJplDistanceExtremum(
            event.bodyA(),
            event.bodyB(),
            coarseBest.time().minus(Duration.ofHours(9)),
            coarseBest.time().plus(Duration.ofHours(9)),
            Duration.ofHours(1),
            findMinimum
        );

        double deltaKm = Math.abs(refinedBest.distanceAu() - event.computedDistanceAu()) * EventCatalogService.AU_KM;
        String summary = "JPL corrected distance event %s at %.8f AU; computed %s at %.8f AU".formatted(
            refinedBest.time(),
            refinedBest.distanceAu(),
            event.computedTimeUtc(),
            event.computedDistanceAu()
        );

        return catalogService.storeValidation(
            event,
            refinedBest.time(),
            refinedBest.distanceAu(),
            null,
            null,
            deltaKm,
            summary
        );
    }

    private CatalogEvent validateElongation(CatalogEvent event) {
        BodyId targetBody = event.bodyA() == BodyId.EARTH ? event.bodyB() : event.bodyA();
        double angleDeg = jplElongation(targetBody, event.computedTimeUtc());
        double computedAngle = event.computedAngleDeg() == null ? angleDeg : event.computedAngleDeg();
        double deltaDeg = Math.abs(signedAngleDifference(angleDeg, computedAngle));
        String summary = "JPL elongation %.6f deg; computed elongation %.6f deg; delta %.6f deg".formatted(
            angleDeg,
            computedAngle,
            deltaDeg
        );

        return catalogService.storeValidation(
            event,
            event.computedTimeUtc(),
            null,
            angleDeg,
            null,
            null,
            summary
        );
    }

    private CatalogEvent validateAngularEvent(CatalogEvent event) {
        double angleDeg = jplRelativeLongitude(event.bodyA(), event.bodyB(), event.computedTimeUtc());
        double computedAngle = event.computedAngleDeg() == null ? angleDeg : event.computedAngleDeg();
        double deltaDeg = Math.abs(signedAngleDifference(angleDeg, computedAngle));
        String summary = "JPL angle %.6f deg; computed angle %.6f deg; delta %.6f deg".formatted(
            angleDeg,
            computedAngle,
            deltaDeg
        );

        return catalogService.storeValidation(
            event,
            event.computedTimeUtc(),
            null,
            angleDeg,
            null,
            null,
            summary
        );
    }

    private DistanceSample findJplDistanceExtremum(
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to,
        Duration step,
        boolean findMinimum
    ) {
        DistanceSample best = sampleJplDistance(bodyA, bodyB, from);

        for (Instant time = from.plus(step); !time.isAfter(to); time = time.plus(step)) {
            DistanceSample sample = sampleJplDistance(bodyA, bodyB, time);
            if (findMinimum ? sample.distanceAu() < best.distanceAu() : sample.distanceAu() > best.distanceAu()) {
                best = sample;
            }
        }

        return new DistanceSample(best.time().truncatedTo(ChronoUnit.SECONDS), best.distanceAu());
    }

    private double jplElongation(BodyId targetBody, Instant time) {
        JplVector earthVector = horizonsClient.vector(BodyId.EARTH, time);
        JplVector targetVector = horizonsClient.vector(targetBody, time);
        Vector3Au earth = earthVector.positionAu();
        Vector3Au target = targetVector.positionAu();
        Vector3Au geoPlanet = target.minus(earth);
        Vector3Au sunDirection = earth.negate();
        return geoPlanet.angleBetweenDeg(sunDirection);
    }

    private DistanceSample findJplDistanceMinimum(
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to,
        Duration step
    ) {
        return findJplDistanceExtremum(bodyA, bodyB, from, to, step, true);
    }

    private DistanceSample sampleJplDistance(BodyId bodyA, BodyId bodyB, Instant time) {
        JplVector vectorA = horizonsClient.vector(bodyA, time);
        JplVector vectorB = horizonsClient.vector(bodyB, time);
        return new DistanceSample(time, vectorA.positionAu().distanceTo(vectorB.positionAu()));
    }

    private DistanceSample sampleJplHeliocentricDistance(BodyId body, Instant time) {
        JplVector vector = horizonsClient.vector(body, time);
        return new DistanceSample(time, vector.positionAu().magnitude());
    }

    private DistanceSample findJplHeliocentricMinimum(BodyId body, Instant from, Instant to, Duration step) {
        DistanceSample best = sampleJplHeliocentricDistance(body, from);

        for (Instant time = from.plus(step); !time.isAfter(to); time = time.plus(step)) {
            DistanceSample sample = sampleJplHeliocentricDistance(body, time);
            if (sample.distanceAu() < best.distanceAu()) {
                best = sample;
            }
        }

        return new DistanceSample(best.time().truncatedTo(ChronoUnit.SECONDS), best.distanceAu());
    }

    private double jplRelativeLongitude(BodyId bodyA, BodyId bodyB, Instant time) {
        JplVector earthVector = horizonsClient.vector(BodyId.EARTH, time);
        BodyId targetBody = bodyA == BodyId.EARTH ? bodyB : bodyA;
        JplVector targetVector = horizonsClient.vector(targetBody, time);
        Vector3Au earth = earthVector.positionAu();
        Vector3Au target = targetVector.positionAu();
        double targetLongitude = longitudeDeg(target.x() - earth.x(), target.y() - earth.y());
        double sunLongitude = longitudeDeg(-earth.x(), -earth.y());
        return normalizeDegrees(targetLongitude - sunLongitude);
    }

    private static double longitudeDeg(double x, double y) {
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    private static double normalizeDegrees(double angle) {
        double normalized = angle % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    private static double signedAngleDifference(double angle, double target) {
        double diff = normalizeDegrees(angle - target);
        return diff > 180.0 ? diff - 360.0 : diff;
    }

    private static String sanitizeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "unknown error";
        }

        String stripped = message
            .replaceAll("(?is)<[^>]*>", " ")
            .replaceAll("\\s+", " ")
            .trim();

        if (stripped.length() > 220) {
            return stripped.substring(0, 220) + "...";
        }

        return stripped;
    }

    private record DistanceSample(Instant time, double distanceAu) {
    }
}

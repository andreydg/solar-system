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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    @Async("jplValidationExecutor")
    @EventListener
    public void validateGeneratedEvents(CatalogEventsGeneratedEvent generatedEvent) {
        if (!properties.asyncValidationEnabled()) {
            return;
        }

        for (String eventId : generatedEvent.eventIds()) {
            try {
                validateEvent(eventId);
            } catch (JplHorizonsException exception) {
                if (exception.isTransient()) {
                    // Leave the event PENDING so it can be revalidated later instead of marking it
                    // FAILED on a temporary outage (timeout, HTTP 429/5xx).
                    continue;
                }
                markFailed(eventId, exception);
            } catch (RuntimeException exception) {
                markFailed(eventId, exception);
            }
        }
    }

    private void markFailed(String eventId, RuntimeException exception) {
        catalogService.findById(eventId)
            .ifPresent(event -> catalogService.storeValidationFailure(
                event,
                "JPL validation failed: " + sanitizeFailureMessage(exception)
            ));
    }

    public CatalogEvent validateEvent(String eventId) {
        CatalogEvent event = catalogService.findById(eventId)
            .orElseThrow(() -> new JplHorizonsException("Catalog event not found: " + eventId));

        return switch (event.type()) {
            case CLOSEST_APPROACH -> validateDistanceExtremum(event, true);
            case FARTHEST_APPROACH -> validateDistanceExtremum(event, false);
            case OPPOSITION, CONJUNCTION -> validateAngularEvent(event);
            case GREATEST_ELONGATION -> validateElongationExtremum(event, false);
            case TRANSIT -> validateElongationExtremum(event, true);
            case PERIHELION -> validatePerihelion(event);
            case BRIGHTEST_APPROACH -> validateBrightestApproach(event);
            case STATIONARY, RETROGRADE_START, RETROGRADE_END -> validateMotionEvent(event);
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

    private CatalogEvent validateBrightestApproach(CatalogEvent event) {
        if (event.computedMagnitude() == null) {
            throw new JplHorizonsException("Brightest approach event did not include computed magnitude");
        }

        BodyId target = event.bodyA() == BodyId.EARTH ? event.bodyB() : event.bodyA();

        MagnitudeSample coarseBest = findJplBrightestApproach(
            target,
            event.computedTimeUtc().minus(Duration.ofDays(2)),
            event.computedTimeUtc().plus(Duration.ofDays(2)),
            Duration.ofHours(6)
        );
        MagnitudeSample refinedBest = findJplBrightestApproach(
            target,
            coarseBest.time().minus(Duration.ofHours(9)),
            coarseBest.time().plus(Duration.ofHours(9)),
            Duration.ofHours(1)
        );

        String summary = "JPL corrected brightest approach %s at mag %.4f; computed %s at mag %.4f".formatted(
            refinedBest.time(),
            refinedBest.magnitude(),
            event.computedTimeUtc(),
            event.computedMagnitude()
        );

        return catalogService.storeValidation(
            event,
            refinedBest.time(),
            null,
            null,
            refinedBest.magnitude(),
            null,
            summary
        );
    }

    private MagnitudeSample findJplBrightestApproach(BodyId target, Instant from, Instant to, Duration step) {
        String horizonsStep = horizonsStep(step);
        List<JplVector> targetSamples = horizonsClient.vectors(target, from, to, horizonsStep);
        Map<Instant, Vector3Au> earthSamples = horizonsClient.vectors(BodyId.EARTH, from, to, horizonsStep).stream()
            .collect(Collectors.toMap(JplVector::requestedTimeUtc, JplVector::positionAu, (first, second) -> first));

        MagnitudeSample best = null;
        for (JplVector targetSample : targetSamples) {
            Vector3Au earth = earthSamples.get(targetSample.requestedTimeUtc());
            if (earth == null) {
                continue;
            }

            Vector3Au planet = targetSample.positionAu();
            double helioDist = planet.magnitude();
            double geoDist = planet.distanceTo(earth);

            Vector3Au planetToSun = planet.negate();
            Vector3Au planetToEarth = earth.minus(planet);
            double alphaDeg = planetToSun.angleBetweenDeg(planetToEarth);

            double mag = catalogService.computeVisualMagnitude(target, helioDist, geoDist, alphaDeg);

            if (best == null || mag < best.magnitude()) {
                best = new MagnitudeSample(targetSample.requestedTimeUtc(), mag);
            }
        }

        if (best == null) {
            throw new JplHorizonsException("JPL Horizons returned no overlapping samples for the brightest approach window");
        }

        return new MagnitudeSample(best.time().truncatedTo(ChronoUnit.SECONDS), best.magnitude());
    }

    private CatalogEvent validateElongationExtremum(CatalogEvent event, boolean findMinimum) {
        BodyId targetBody = event.bodyA() == BodyId.EARTH ? event.bodyB() : event.bodyA();
        
        AngleSample coarseBest = findJplElongationExtremum(
            targetBody,
            event.computedTimeUtc().minus(Duration.ofDays(2)),
            event.computedTimeUtc().plus(Duration.ofDays(2)),
            Duration.ofHours(6),
            findMinimum
        );
        AngleSample refinedBest = findJplElongationExtremum(
            targetBody,
            coarseBest.time().minus(Duration.ofHours(9)),
            coarseBest.time().plus(Duration.ofHours(9)),
            Duration.ofHours(1),
            findMinimum
        );

        double computedAngle = event.computedAngleDeg() == null ? refinedBest.angleDeg() : event.computedAngleDeg();
        double deltaDeg = Math.abs(signedAngleDifference(refinedBest.angleDeg(), computedAngle));
        String summary = "JPL corrected elongation %s at %.6f deg; computed %s at %.6f deg; delta %.6f deg".formatted(
            refinedBest.time(),
            refinedBest.angleDeg(),
            event.computedTimeUtc(),
            computedAngle,
            deltaDeg
        );

        return catalogService.storeValidation(
            event,
            refinedBest.time(),
            null,
            refinedBest.angleDeg(),
            null,
            null,
            summary
        );
    }

    private AngleSample findJplElongationExtremum(BodyId targetBody, Instant from, Instant to, Duration step, boolean findMinimum) {
        String horizonsStep = horizonsStep(step);
        List<JplVector> earthSamples = horizonsClient.vectors(BodyId.EARTH, from, to, horizonsStep);
        Map<Instant, Vector3Au> targetSamples = horizonsClient.vectors(targetBody, from, to, horizonsStep).stream()
            .collect(Collectors.toMap(JplVector::requestedTimeUtc, JplVector::positionAu, (first, second) -> first));

        AngleSample best = null;
        for (JplVector earthSample : earthSamples) {
            Vector3Au target = targetSamples.get(earthSample.requestedTimeUtc());
            if (target == null) {
                continue;
            }

            Vector3Au earth = earthSample.positionAu();
            Vector3Au geoPlanet = target.minus(earth);
            Vector3Au sunDirection = earth.negate();
            double angleDeg = geoPlanet.angleBetweenDeg(sunDirection);

            if (best == null || (findMinimum ? angleDeg < best.angleDeg() : angleDeg > best.angleDeg())) {
                best = new AngleSample(earthSample.requestedTimeUtc(), angleDeg);
            }
        }

        if (best == null) {
            throw new JplHorizonsException("JPL Horizons returned no overlapping samples for the elongation window");
        }

        return new AngleSample(best.time().truncatedTo(ChronoUnit.SECONDS), best.angleDeg());
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

    private CatalogEvent validateMotionEvent(CatalogEvent event) {
        BodyId targetBody = event.bodyA() == BodyId.EARTH ? event.bodyB() : event.bodyA();

        Instant coarseBest = findJplStationaryTime(
            targetBody,
            event.computedTimeUtc().minus(Duration.ofDays(2)),
            event.computedTimeUtc().plus(Duration.ofDays(2)),
            Duration.ofHours(6)
        );
        Instant refinedBest = findJplStationaryTime(
            targetBody,
            coarseBest.minus(Duration.ofHours(9)),
            coarseBest.plus(Duration.ofHours(9)),
            Duration.ofHours(1)
        );

        double deltaHours = Math.abs(Duration.between(event.computedTimeUtc(), refinedBest).toMinutes() / 60.0);
        String summary = "JPL corrected stationary time %s; computed %s; delta %.2f hours".formatted(
            refinedBest,
            event.computedTimeUtc(),
            deltaHours
        );

        return catalogService.storeValidation(
            event,
            refinedBest,
            null,
            null,
            null,
            null,
            summary
        );
    }

    private Instant findJplStationaryTime(BodyId targetBody, Instant from, Instant to, Duration step) {
        String horizonsStep = horizonsStep(step);
        List<JplVector> earthSamples = horizonsClient.vectors(BodyId.EARTH, from, to, horizonsStep);
        Map<Instant, Vector3Au> targetSamples = horizonsClient.vectors(targetBody, from, to, horizonsStep).stream()
            .collect(Collectors.toMap(JplVector::requestedTimeUtc, JplVector::positionAu, (first, second) -> first));

        Instant bestTime = null;
        double minRate = Double.MAX_VALUE;
        Double prevLongitude = null;

        for (JplVector earthSample : earthSamples) {
            Vector3Au target = targetSamples.get(earthSample.requestedTimeUtc());
            if (target == null) {
                continue;
            }

            Vector3Au earth = earthSample.positionAu();
            Vector3Au geoPlanet = target.minus(earth);
            double longitude = longitudeDeg(geoPlanet.x(), geoPlanet.y());

            if (prevLongitude != null) {
                double rate = Math.abs(signedAngleDifference(longitude, prevLongitude));
                if (rate < minRate) {
                    minRate = rate;
                    bestTime = earthSample.requestedTimeUtc();
                }
            }
            prevLongitude = longitude;
        }

        if (bestTime == null) {
            throw new JplHorizonsException("JPL Horizons returned no overlapping samples for the motion window");
        }

        return bestTime.truncatedTo(ChronoUnit.SECONDS);
    }

    private DistanceSample findJplDistanceExtremum(
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to,
        Duration step,
        boolean findMinimum
    ) {
        // Fetch the whole window in two batched range requests instead of one HTTP call per
        // grid point per body (which was ~70 sequential calls for a single distance event).
        String horizonsStep = horizonsStep(step);
        List<JplVector> samplesA = horizonsClient.vectors(bodyA, from, to, horizonsStep);
        Map<Instant, Vector3Au> samplesB = horizonsClient.vectors(bodyB, from, to, horizonsStep).stream()
            .collect(Collectors.toMap(JplVector::requestedTimeUtc, JplVector::positionAu, (first, second) -> first));

        DistanceSample best = null;
        for (JplVector sampleA : samplesA) {
            Vector3Au positionB = samplesB.get(sampleA.requestedTimeUtc());
            if (positionB == null) {
                continue;
            }

            double distanceAu = sampleA.positionAu().distanceTo(positionB);
            if (best == null || (findMinimum ? distanceAu < best.distanceAu() : distanceAu > best.distanceAu())) {
                best = new DistanceSample(sampleA.requestedTimeUtc(), distanceAu);
            }
        }

        if (best == null) {
            throw new JplHorizonsException("JPL Horizons returned no overlapping samples for the distance window");
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

    private DistanceSample findJplHeliocentricMinimum(BodyId body, Instant from, Instant to, Duration step) {
        DistanceSample best = null;
        for (JplVector sample : horizonsClient.vectors(body, from, to, horizonsStep(step))) {
            double distanceAu = sample.positionAu().magnitude();
            if (best == null || distanceAu < best.distanceAu()) {
                best = new DistanceSample(sample.requestedTimeUtc(), distanceAu);
            }
        }

        if (best == null) {
            throw new JplHorizonsException("JPL Horizons returned no samples for the heliocentric distance window");
        }

        return new DistanceSample(best.time().truncatedTo(ChronoUnit.SECONDS), best.distanceAu());
    }

    private static String horizonsStep(Duration step) {
        long minutes = Math.max(1, step.toMinutes());
        return minutes + "m";
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

    private record MagnitudeSample(Instant time, double magnitude) {
    }

    private record AngleSample(Instant time, double angleDeg) {
    }
}

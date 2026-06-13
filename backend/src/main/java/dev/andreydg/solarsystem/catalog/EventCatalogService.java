package dev.andreydg.solarsystem.catalog;

import dev.andreydg.solarsystem.ephemeris.EphemerisProvider;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import dev.andreydg.solarsystem.storage.EventCatalogRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class EventCatalogService {
    public static final double AU_KM = 149_597_870.7;
    private static final Duration DEFAULT_COARSE_STEP = Duration.ofDays(5);
    private static final Duration DAILY_STEP = Duration.ofDays(1);
    private static final double TRANSIT_ELONGATION_DEG = 0.5;
    private static final Map<BodyId, Double> BASE_MAGNITUDE = new EnumMap<>(BodyId.class);

    static {
        BASE_MAGNITUDE.put(BodyId.MERCURY, -0.6);
        BASE_MAGNITUDE.put(BodyId.VENUS, -4.4);
        BASE_MAGNITUDE.put(BodyId.MARS, -1.52);
        BASE_MAGNITUDE.put(BodyId.JUPITER, -9.4);
        BASE_MAGNITUDE.put(BodyId.SATURN, -8.9);
        BASE_MAGNITUDE.put(BodyId.URANUS, -7.2);
        BASE_MAGNITUDE.put(BodyId.NEPTUNE, -6.9);
    }

    private final EphemerisProvider ephemerisProvider;
    private final EventCatalogRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public EventCatalogService(
        EphemerisProvider ephemerisProvider,
        EventCatalogRepository repository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.ephemerisProvider = ephemerisProvider;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public List<CatalogEvent> query(EventType type, BodyId bodyA, BodyId bodyB, Instant from, Instant to) {
        List<CatalogEvent> events = repository.find(type, bodyA, bodyB, from, to).stream()
            .filter(this::isStoredEventValid)
            .toList();
        List<String> retryableIds = events.stream()
            .filter(event -> event.validationStatus() == ValidationStatus.FAILED)
            .filter(event -> event.type().supportsJplValidation())
            .map(CatalogEvent::id)
            .toList();

        if (!retryableIds.isEmpty()) {
            eventPublisher.publishEvent(new CatalogEventsGeneratedEvent(retryableIds));
        }

        return events;
    }

    public List<CatalogEvent> generate(EventType type, BodyId bodyA, BodyId bodyB, Instant from, Instant to) {
        BodyPair pair = BodyPair.of(bodyA, bodyB);
        List<CatalogEvent> events = switch (type) {
            case CLOSEST_APPROACH -> generateExtremalApproaches(
                EventType.CLOSEST_APPROACH,
                pair.bodyA(),
                pair.bodyB(),
                from,
                to,
                true
            );
            case FARTHEST_APPROACH -> generateExtremalApproaches(
                EventType.FARTHEST_APPROACH,
                pair.bodyA(),
                pair.bodyB(),
                from,
                to,
                false
            );
            case OPPOSITION -> generateAngularEventForEarthPair(type, pair, from, to, 180.0);
            case CONJUNCTION -> generateAngularEventForEarthPair(type, pair, from, to, 0.0);
            case GREATEST_ELONGATION -> generateGreatestElongations(pair, from, to);
            case STATIONARY -> generateMotionEvents(EventType.STATIONARY, pair, from, to, MotionCrossing.ANY_ZERO);
            case RETROGRADE_START -> generateMotionEvents(EventType.RETROGRADE_START, pair, from, to, MotionCrossing.POSITIVE_TO_NEGATIVE);
            case RETROGRADE_END -> generateMotionEvents(EventType.RETROGRADE_END, pair, from, to, MotionCrossing.NEGATIVE_TO_POSITIVE);
            case TRANSIT -> generateTransits(pair, from, to);
            case BRIGHTEST_APPROACH -> generateBrightestApproaches(pair, from, to);
        };

        List<CatalogEvent> validEvents = events.stream()
            .filter(this::isStoredEventValid)
            .toList();

        repository.upsertAll(validEvents);

        List<String> validatableIds = validEvents.stream()
            .filter(event -> event.type().supportsJplValidation())
            .map(CatalogEvent::id)
            .toList();

        if (!validatableIds.isEmpty()) {
            eventPublisher.publishEvent(new CatalogEventsGeneratedEvent(validatableIds));
        }

        return validEvents;
    }

    public List<CatalogEvent> listValidatedEvents() {
        return repository.findAllValidated().stream()
            .filter(this::isStoredEventValid)
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    public CatalogEvent storeValidation(
        CatalogEvent event,
        Instant validatedTimeUtc,
        Double validatedDistanceAu,
        Double validatedAngleDeg,
        Double validatedMagnitude,
        Double jplDeltaKm,
        String summary
    ) {
        CatalogEvent updated = new CatalogEvent(
            event.id(),
            event.type(),
            event.bodyA(),
            event.bodyB(),
            event.computedTimeUtc(),
            event.computedDistanceAu(),
            event.computedAngleDeg(),
            event.computedMagnitude(),
            event.computedSource(),
            validatedTimeUtc,
            validatedDistanceAu,
            validatedAngleDeg,
            validatedMagnitude,
            "JPL_HORIZONS",
            ValidationStatus.VALIDATED,
            event.rangeStartUtc(),
            event.rangeEndUtc(),
            event.generatedAtUtc(),
            Instant.now(),
            jplDeltaKm,
            summary
        );
        repository.upsert(updated);
        return updated;
    }

    public CatalogEvent storeValidationFailure(CatalogEvent event, String summary) {
        CatalogEvent updated = new CatalogEvent(
            event.id(),
            event.type(),
            event.bodyA(),
            event.bodyB(),
            event.computedTimeUtc(),
            event.computedDistanceAu(),
            event.computedAngleDeg(),
            event.computedMagnitude(),
            event.computedSource(),
            event.validatedTimeUtc(),
            event.validatedDistanceAu(),
            event.validatedAngleDeg(),
            event.validatedMagnitude(),
            event.validatedSource(),
            ValidationStatus.FAILED,
            event.rangeStartUtc(),
            event.rangeEndUtc(),
            event.generatedAtUtc(),
            Instant.now(),
            event.jplDeltaKm(),
            summary
        );
        repository.upsert(updated);
        return updated;
    }

    public java.util.Optional<CatalogEvent> findById(String id) {
        return repository.findById(id);
    }

    private List<CatalogEvent> generateExtremalApproaches(
        EventType type,
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to,
        boolean findMinimum
    ) {
        List<DistanceSample> candidates = new ArrayList<>();
        DistanceSample previous = null;
        DistanceSample current = sampleDistance(bodyA, bodyB, from);

        for (Instant time = from.plus(DEFAULT_COARSE_STEP); !time.isAfter(to); time = time.plus(DEFAULT_COARSE_STEP)) {
            DistanceSample next = sampleDistance(bodyA, bodyB, time);

            if (previous != null && isLocalExtremum(previous, current, next, findMinimum)) {
                candidates.add(current);
            }

            previous = current;
            current = next;
        }

        if (candidates.isEmpty()) {
            candidates.add(refineDistanceExtremum(bodyA, bodyB, from, to, Duration.ofHours(6), findMinimum));
        }

        Instant generatedAt = Instant.now();
        return candidates.stream()
            .map(candidate -> refineDistanceExtremum(
                bodyA,
                bodyB,
                candidate.time().minus(DEFAULT_COARSE_STEP.multipliedBy(2)),
                candidate.time().plus(DEFAULT_COARSE_STEP.multipliedBy(2)),
                Duration.ofHours(1),
                findMinimum
            ))
            .distinct()
            .map(sample -> toEvent(
                type,
                bodyA,
                bodyB,
                from,
                to,
                generatedAt,
                sample.time(),
                sample.distanceAu(),
                null,
                null
            ))
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    private List<CatalogEvent> generateAngularEvents(
        EventType type,
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to,
        double targetAngleDeg
    ) {
        List<CatalogEvent> events = new ArrayList<>();
        Instant generatedAt = Instant.now();
        AngularSample previous = sampleRelativeLongitude(bodyA, bodyB, from, targetAngleDeg);

        for (Instant time = from.plus(DAILY_STEP); !time.isAfter(to); time = time.plus(DAILY_STEP)) {
            AngularSample current = sampleRelativeLongitude(bodyA, bodyB, time, targetAngleDeg);

            if (crossesZero(previous.offsetDeg(), current.offsetDeg())) {
                AngularSample refined = refineAngularCrossing(bodyA, bodyB, previous.time(), current.time(), targetAngleDeg);
                events.add(toEvent(
                    type,
                    bodyA,
                    bodyB,
                    from,
                    to,
                    generatedAt,
                    refined.time(),
                    null,
                    refined.angleDeg(),
                    null
                ));
            }

            previous = current;
        }

        return events.stream()
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    private List<CatalogEvent> generateGreatestElongations(BodyPair pair, Instant from, Instant to) {
        BodyPair earthPair = earthObserverPair(pair);
        BodyId target = targetBody(earthPair);
        requireInnerPlanet(target);

        List<ElongationSample> candidates = new ArrayList<>();
        ElongationSample previous = null;
        ElongationSample current = sampleElongation(target, from);

        for (Instant time = from.plus(DAILY_STEP); !time.isAfter(to); time = time.plus(DAILY_STEP)) {
            ElongationSample next = sampleElongation(target, time);

            if (previous != null
                && current.elongationDeg() >= previous.elongationDeg()
                && current.elongationDeg() >= next.elongationDeg()) {
                candidates.add(current);
            }

            previous = current;
            current = next;
        }

        Instant generatedAt = Instant.now();
        return candidates.stream()
            .map(sample -> refineElongationMaximum(target, sample.time().minus(DAILY_STEP), sample.time().plus(DAILY_STEP)))
            .map(sample -> toEvent(
                EventType.GREATEST_ELONGATION,
                earthPair.bodyA(),
                earthPair.bodyB(),
                from,
                to,
                generatedAt,
                sample.time(),
                null,
                sample.elongationDeg(),
                null
            ))
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    private List<CatalogEvent> generateMotionEvents(
        EventType type,
        BodyPair pair,
        Instant from,
        Instant to,
        MotionCrossing crossing
    ) {
        BodyPair earthPair = earthObserverPair(pair);
        BodyId target = targetBody(earthPair);
        if (target == BodyId.EARTH) {
            throw new IllegalArgumentException("Motion events require Earth and one target body");
        }

        List<CatalogEvent> events = new ArrayList<>();
        Instant generatedAt = Instant.now();
        RateSample previous = sampleLongitudeRate(target, from);

        for (Instant time = from.plus(DAILY_STEP); !time.isAfter(to); time = time.plus(DAILY_STEP)) {
            RateSample current = sampleLongitudeRate(target, time);

            if (crossing.matches(previous.rateDegPerDay(), current.rateDegPerDay())) {
                Instant refinedTime = refineRateCrossing(target, previous.time(), current.time(), crossing);
                events.add(toEvent(
                    type,
                    earthPair.bodyA(),
                    earthPair.bodyB(),
                    from,
                    to,
                    generatedAt,
                    refinedTime,
                    null,
                    null,
                    null
                ));
            }

            previous = current;
        }

        return events.stream()
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    private List<CatalogEvent> generateTransits(BodyPair pair, Instant from, Instant to) {
        BodyPair earthPair = earthObserverPair(pair);
        BodyId target = targetBody(earthPair);
        requireInnerPlanet(target);

        List<ElongationSample> candidates = new ArrayList<>();
        ElongationSample previous = null;
        ElongationSample current = sampleElongation(target, from);

        for (Instant time = from.plus(DAILY_STEP); !time.isAfter(to); time = time.plus(DAILY_STEP)) {
            ElongationSample next = sampleElongation(target, time);

            if (previous != null
                && current.elongationDeg() <= previous.elongationDeg()
                && current.elongationDeg() <= next.elongationDeg()
                && current.elongationDeg() <= TRANSIT_ELONGATION_DEG
                && isInferiorConjunction(target, current.time())) {
                candidates.add(current);
            }

            previous = current;
            current = next;
        }

        Instant generatedAt = Instant.now();
        return candidates.stream()
            .map(sample -> refineElongationMinimum(
                target,
                sample.time().minus(DAILY_STEP),
                sample.time().plus(DAILY_STEP)
            ))
            .filter(sample -> sample.elongationDeg() <= TRANSIT_ELONGATION_DEG)
            .filter(sample -> isInferiorConjunction(target, sample.time()))
            .map(sample -> toEvent(
                EventType.TRANSIT,
                earthPair.bodyA(),
                earthPair.bodyB(),
                from,
                to,
                generatedAt,
                sample.time(),
                null,
                sample.elongationDeg(),
                null
            ))
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    private List<CatalogEvent> generateBrightestApproaches(BodyPair pair, Instant from, Instant to) {
        BodyPair earthPair = earthObserverPair(pair);
        BodyId target = targetBody(earthPair);
        if (target == BodyId.EARTH) {
            throw new IllegalArgumentException("Brightest approach events require Earth and one target body");
        }

        List<MagnitudeSample> candidates = new ArrayList<>();
        MagnitudeSample previous = null;
        MagnitudeSample current = sampleVisualMagnitude(target, from);

        for (Instant time = from.plus(DEFAULT_COARSE_STEP); !time.isAfter(to); time = time.plus(DEFAULT_COARSE_STEP)) {
            MagnitudeSample next = sampleVisualMagnitude(target, time);

            if (previous != null
                && current.magnitude() <= previous.magnitude()
                && current.magnitude() <= next.magnitude()) {
                candidates.add(current);
            }

            previous = current;
            current = next;
        }

        Instant generatedAt = Instant.now();
        return candidates.stream()
            .map(sample -> refineMagnitudeMinimum(
                target,
                sample.time().minus(DEFAULT_COARSE_STEP.multipliedBy(2)),
                sample.time().plus(DEFAULT_COARSE_STEP.multipliedBy(2))
            ))
            .map(sample -> toEvent(
                EventType.BRIGHTEST_APPROACH,
                earthPair.bodyA(),
                earthPair.bodyB(),
                from,
                to,
                generatedAt,
                sample.time(),
                null,
                null,
                sample.magnitude()
            ))
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    private CatalogEvent toEvent(
        EventType type,
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to,
        Instant generatedAt,
        Instant time,
        Double distanceAu,
        Double angleDeg,
        Double magnitude
    ) {
        return new CatalogEvent(
            deterministicId(type, bodyA, bodyB, time),
            type,
            bodyA,
            bodyB,
            time,
            distanceAu,
            angleDeg,
            magnitude,
            ephemerisProvider.source(),
            null,
            null,
            null,
            null,
            null,
            ValidationStatus.PENDING,
            from,
            to,
            generatedAt,
            null,
            null,
            null
        );
    }

    private DistanceSample refineDistanceExtremum(
        BodyId bodyA,
        BodyId bodyB,
        Instant windowStart,
        Instant windowEnd,
        Duration finalStep,
        boolean findMinimum
    ) {
        Instant start = windowStart;
        Instant end = windowEnd;
        DistanceSample best = sampleWindow(bodyA, bodyB, start, end, Duration.ofDays(1), findMinimum);

        for (Duration step : List.of(Duration.ofHours(6), Duration.ofHours(1), finalStep)) {
            start = best.time().minus(step.multipliedBy(12));
            end = best.time().plus(step.multipliedBy(12));
            best = sampleWindow(bodyA, bodyB, start, end, step, findMinimum);
        }

        return best;
    }

    private DistanceSample sampleWindow(
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to,
        Duration step,
        boolean findMinimum
    ) {
        DistanceSample best = sampleDistance(bodyA, bodyB, from);

        for (Instant time = from; !time.isAfter(to); time = time.plus(step)) {
            DistanceSample sample = sampleDistance(bodyA, bodyB, time);

            if (findMinimum ? sample.distanceAu() < best.distanceAu() : sample.distanceAu() > best.distanceAu()) {
                best = sample;
            }
        }

        return best;
    }

    private DistanceSample sampleDistance(BodyId bodyA, BodyId bodyB, Instant time) {
        Vector3Au positionA = ephemerisProvider.heliocentricPosition(bodyA, time);
        Vector3Au positionB = ephemerisProvider.heliocentricPosition(bodyB, time);
        return new DistanceSample(time, positionA.distanceTo(positionB));
    }

    private ElongationSample sampleElongation(BodyId target, Instant time) {
        Vector3Au earth = ephemerisProvider.heliocentricPosition(BodyId.EARTH, time);
        Vector3Au planet = ephemerisProvider.heliocentricPosition(target, time);
        Vector3Au geoPlanet = planet.minus(earth);
        Vector3Au sunDirection = earth.negate();
        return new ElongationSample(time, geoPlanet.angleBetweenDeg(sunDirection));
    }

    private ElongationSample refineElongationMaximum(BodyId target, Instant start, Instant end) {
        return refineElongationExtremum(target, start, end, false);
    }

    private ElongationSample refineElongationMinimum(BodyId target, Instant start, Instant end) {
        return refineElongationExtremum(target, start, end, true);
    }

    private ElongationSample refineElongationExtremum(BodyId target, Instant start, Instant end, boolean findMinimum) {
        ElongationSample best = sampleElongation(target, start);

        for (Instant time = start; !time.isAfter(end); time = time.plus(Duration.ofHours(6))) {
            ElongationSample sample = sampleElongation(target, time);
            if (findMinimum ? sample.elongationDeg() < best.elongationDeg() : sample.elongationDeg() > best.elongationDeg()) {
                best = sample;
            }
        }

        return new ElongationSample(best.time().truncatedTo(ChronoUnit.SECONDS), best.elongationDeg());
    }

    private MagnitudeSample refineMagnitudeMinimum(BodyId target, Instant start, Instant end) {
        MagnitudeSample best = sampleVisualMagnitude(target, start);

        for (Instant time = start; !time.isAfter(end); time = time.plus(Duration.ofHours(12))) {
            MagnitudeSample sample = sampleVisualMagnitude(target, time);
            if (sample.magnitude() < best.magnitude()) {
                best = sample;
            }
        }

        return new MagnitudeSample(best.time().truncatedTo(ChronoUnit.SECONDS), best.magnitude());
    }

    private MagnitudeSample sampleVisualMagnitude(BodyId target, Instant time) {
        Vector3Au earth = ephemerisProvider.heliocentricPosition(BodyId.EARTH, time);
        Vector3Au planet = ephemerisProvider.heliocentricPosition(target, time);
        double heliocentricDistanceAu = planet.magnitude();
        double geocentricDistanceAu = planet.distanceTo(earth);
        double baseMagnitude = BASE_MAGNITUDE.getOrDefault(target, 0.0);
        double magnitude = baseMagnitude + 5.0 * Math.log10(heliocentricDistanceAu * geocentricDistanceAu);
        return new MagnitudeSample(time, magnitude);
    }

    private RateSample sampleLongitudeRate(BodyId target, Instant time) {
        double startLongitude = geocentricLongitude(target, time);
        double endLongitude = geocentricLongitude(target, time.plus(DAILY_STEP));
        double rateDegPerDay = signedAngleDifference(endLongitude, startLongitude);
        return new RateSample(time.plus(DAILY_STEP.dividedBy(2)), rateDegPerDay);
    }

    private Instant refineRateCrossing(BodyId target, Instant start, Instant end, MotionCrossing crossing) {
        Instant low = start;
        Instant high = end;
        RateSample lowSample = sampleLongitudeRateAt(target, low, Duration.between(low, high));

        for (int i = 0; i < 24; i++) {
            Instant mid = low.plus(Duration.between(low, high).dividedBy(2));
            RateSample midSample = sampleLongitudeRateAt(target, mid, Duration.between(mid, high));

            if (crossing.matches(lowSample.rateDegPerDay(), midSample.rateDegPerDay())) {
                high = mid;
            } else {
                low = mid;
                lowSample = midSample;
            }
        }

        return low.plus(Duration.between(low, high).dividedBy(2)).truncatedTo(ChronoUnit.SECONDS);
    }

    private RateSample sampleLongitudeRateAt(BodyId target, Instant time, Duration step) {
        if (step.isZero()) {
            step = Duration.ofHours(12);
        }

        double startLongitude = geocentricLongitude(target, time);
        double endLongitude = geocentricLongitude(target, time.plus(step));
        double rateDegPerDay = signedAngleDifference(endLongitude, startLongitude) * Duration.ofDays(1).toHours() / step.toHours();
        return new RateSample(time.plus(step.dividedBy(2)), rateDegPerDay);
    }

    private double geocentricLongitude(BodyId target, Instant time) {
        Vector3Au earth = ephemerisProvider.heliocentricPosition(BodyId.EARTH, time);
        Vector3Au planet = ephemerisProvider.heliocentricPosition(target, time);
        Vector3Au geo = planet.minus(earth);
        return longitudeDeg(geo.x(), geo.y());
    }

    private boolean isStoredEventValid(CatalogEvent event) {
        if (event.type() != EventType.TRANSIT) {
            return true;
        }

        BodyId target = targetBody(BodyPair.of(event.bodyA(), event.bodyB()));
        return isInferiorConjunction(target, event.computedTimeUtc())
            && sampleElongation(target, event.computedTimeUtc()).elongationDeg() <= TRANSIT_ELONGATION_DEG;
    }

    private boolean isInferiorConjunction(BodyId target, Instant time) {
        Vector3Au earth = ephemerisProvider.heliocentricPosition(BodyId.EARTH, time);
        Vector3Au planet = ephemerisProvider.heliocentricPosition(target, time);

        if (earth.dot(planet) <= 0.0) {
            return false;
        }

        return planet.magnitude() < earth.magnitude();
    }

    private AngularSample refineAngularCrossing(BodyId bodyA, BodyId bodyB, Instant start, Instant end, double targetAngle) {
        Instant low = start;
        Instant high = end;
        AngularSample lowSample = sampleRelativeLongitude(bodyA, bodyB, low, targetAngle);

        for (int i = 0; i < 30; i++) {
            Instant mid = low.plus(Duration.between(low, high).dividedBy(2));
            AngularSample midSample = sampleRelativeLongitude(bodyA, bodyB, mid, targetAngle);

            if (crossesZero(lowSample.offsetDeg(), midSample.offsetDeg())) {
                high = mid;
            } else {
                low = mid;
                lowSample = midSample;
            }
        }

        Instant time = low.plus(Duration.between(low, high).dividedBy(2)).truncatedTo(ChronoUnit.SECONDS);
        return sampleRelativeLongitude(bodyA, bodyB, time, targetAngle);
    }

    private AngularSample sampleRelativeLongitude(BodyId bodyA, BodyId bodyB, Instant time, double targetAngleDeg) {
        Vector3Au earth = ephemerisProvider.heliocentricPosition(BodyId.EARTH, time);
        Vector3Au target = bodyA == BodyId.EARTH
            ? ephemerisProvider.heliocentricPosition(bodyB, time)
            : ephemerisProvider.heliocentricPosition(bodyA, time);

        double targetLongitude = longitudeDeg(target.x() - earth.x(), target.y() - earth.y());
        double sunLongitude = longitudeDeg(-earth.x(), -earth.y());
        double angle = normalizeDegrees(targetLongitude - sunLongitude);
        double offset = signedAngleDifference(angle, targetAngleDeg);

        return new AngularSample(time, angle, offset);
    }

    private static boolean isLocalExtremum(
        DistanceSample previous,
        DistanceSample current,
        DistanceSample next,
        boolean findMinimum
    ) {
        if (findMinimum) {
            return current.distanceAu() <= previous.distanceAu() && current.distanceAu() <= next.distanceAu();
        }

        return current.distanceAu() >= previous.distanceAu() && current.distanceAu() >= next.distanceAu();
    }

    private static boolean crossesZero(double a, double b) {
        return a == 0 || b == 0 || Math.signum(a) != Math.signum(b);
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

    private static String deterministicId(EventType type, BodyId bodyA, BodyId bodyB, Instant time) {
        return "%s_%s_%s_%s".formatted(
            type.apiValue(),
            bodyA.apiValue(),
            bodyB.apiValue(),
            time.truncatedTo(ChronoUnit.HOURS).toString().replace(":", "")
        );
    }

    private static BodyId targetBody(BodyPair pair) {
        return pair.bodyA() == BodyId.EARTH ? pair.bodyB() : pair.bodyA();
    }

    private static void requireInnerPlanet(BodyId target) {
        if (target != BodyId.MERCURY && target != BodyId.VENUS) {
            throw new IllegalArgumentException("Inner-planet events require Mercury or Venus");
        }
    }

    private record BodyPair(BodyId bodyA, BodyId bodyB) {
        static BodyPair of(BodyId bodyA, BodyId bodyB) {
            return bodyA.ordinal() <= bodyB.ordinal()
                ? new BodyPair(bodyA, bodyB)
                : new BodyPair(bodyB, bodyA);
        }
    }

    private static BodyPair requireEarthPair(BodyPair pair) {
        if (pair.bodyA() != BodyId.EARTH && pair.bodyB() != BodyId.EARTH) {
            throw new IllegalArgumentException("Earth-observer events require Earth and one target body");
        }

        return pair;
    }

    private static BodyPair earthObserverPair(BodyPair pair) {
        BodyPair earthPair = requireEarthPair(pair);
        return new BodyPair(BodyId.EARTH, targetBody(earthPair));
    }

    private List<CatalogEvent> generateAngularEventForEarthPair(
        EventType type,
        BodyPair pair,
        Instant from,
        Instant to,
        double targetAngleDeg
    ) {
        BodyPair earthPair = earthObserverPair(pair);
        return generateAngularEvents(type, earthPair.bodyA(), earthPair.bodyB(), from, to, targetAngleDeg);
    }

    private enum MotionCrossing {
        ANY_ZERO {
            @Override
            boolean matches(double previousRate, double currentRate) {
                return crossesZero(previousRate, currentRate);
            }
        },
        POSITIVE_TO_NEGATIVE {
            @Override
            boolean matches(double previousRate, double currentRate) {
                return previousRate > 0.0 && currentRate < 0.0;
            }
        },
        NEGATIVE_TO_POSITIVE {
            @Override
            boolean matches(double previousRate, double currentRate) {
                return previousRate < 0.0 && currentRate > 0.0;
            }
        };

        abstract boolean matches(double previousRate, double currentRate);
    }

    private record DistanceSample(Instant time, double distanceAu) {
    }

    private record AngularSample(Instant time, double angleDeg, double offsetDeg) {
    }

    private record ElongationSample(Instant time, double elongationDeg) {
    }

    private record MagnitudeSample(Instant time, double magnitude) {
    }

    private record RateSample(Instant time, double rateDegPerDay) {
    }
}

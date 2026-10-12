package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class JplValidationService {
    private final EventCatalogService catalog;
    private final JplHorizonsClient horizons;
    public JplValidationService(EventCatalogService catalog, JplHorizonsClient horizons) {
        this.catalog = catalog;
        this.horizons = horizons;
    }
    public JplVector position(BodyId body, Instant time) { return horizons.vector(body, time); }

    /** Pure with respect to storage: only the fenced worker can commit the returned result. */
    public CatalogEvent validateEvent(String eventId) {
        var event = catalog.findById(eventId).orElseThrow(() -> new JplHorizonsException("Catalog event not found"));
        if (!EventPairing.isValid(event.type(), event.bodyA(), event.bodyB())) throw new JplHorizonsException("Invalid event pair");
        var coarse = frames(event, event.computedTimeUtc().minus(Duration.ofDays(7)),
            event.computedTimeUtc().plus(Duration.ofDays(7)), "360m");
        var candidate = locate(event, coarse, event.computedTimeUtc(), false);
        var fine = frames(event, candidate.time().minus(Duration.ofHours(9)), candidate.time().plus(Duration.ofHours(9)), "5m");
        var refined = locate(event, fine, candidate.time(), true);
        if (event.type() == EventType.TRANSIT) checkTransit(event, fine, refined);
        Double distance = null, angle = null, magnitude = null, delta = null;
        switch (event.type()) {
            case CLOSEST_APPROACH, FARTHEST_APPROACH, PERIHELION -> {
                distance = refined.value();
                if (event.computedDistanceAu() != null) delta = Math.abs(distance - event.computedDistanceAu()) * EventCatalogService.AU_KM;
            }
            case OPPOSITION, CONJUNCTION, GREATEST_ELONGATION, TRANSIT -> angle = refined.value();
            case BRIGHTEST_APPROACH -> magnitude = refined.value();
            default -> { }
        }
        String qualification = event.type() == EventType.BRIGHTEST_APPROACH ? "JPL geometry with approximate photometry" : "JPL geometric event";
        return event.validated(refined.time(), distance, angle, magnitude, delta,
            qualification + "; bracket/direction checked; 5-minute refinement grid; not a local visibility prediction");
    }

    private EventGeometry.Sample locate(CatalogEvent event, List<Frame> frames, Instant anchor, boolean refined) {
        return EventGeometry.locate(event.type(), frames.stream().map(frame -> new EventGeometry.Sample(frame.time(), frame.value())).toList(), anchor, refined);
    }

    private List<Frame> frames(CatalogEvent event, Instant from, Instant to, String step) {
        var samples = rawFrames(event, from, to, step);
        Duration expectedStep = Duration.ofMinutes(Long.parseLong(step.replace("m", "")));
        if (samples.isEmpty() || samples.getFirst().time().isAfter(from.plus(expectedStep))
            || samples.getLast().time().isBefore(to.minus(expectedStep))) throw new JplHorizonsException("Incomplete JPL validation window");
        for (int i = 1; i < samples.size(); i++) {
            if (Duration.between(samples.get(i - 1).time(), samples.get(i).time()).compareTo(expectedStep) > 0) {
                throw new JplHorizonsException("Missing JPL samples within validation window");
            }
        }
        return samples;
    }
    private List<Frame> rawFrames(CatalogEvent event, Instant from, Instant to, String step) {
        if (event.type() == EventType.PERIHELION) {
            return horizons.vectors(target(event), from, to, step).stream()
                .map(vector -> new Frame(vector.requestedTimeUtc(), vector.positionAu(), vector.positionAu(), vector.positionAu().magnitude()))
                .sorted(Comparator.comparing(Frame::time)).toList();
        }
        BodyId first = event.type() == EventType.CLOSEST_APPROACH || event.type() == EventType.FARTHEST_APPROACH ? event.bodyA() : BodyId.EARTH;
        BodyId second = first == BodyId.EARTH ? target(event) : event.bodyB();
        Map<Instant, Vector3Au> others = horizons.vectors(second, from, to, step).stream()
            .collect(Collectors.toMap(JplVector::requestedTimeUtc, JplVector::positionAu, (a, b) -> a));
        return horizons.vectors(first, from, to, step).stream().filter(vector -> others.containsKey(vector.requestedTimeUtc()))
            .map(vector -> {
                var a = vector.positionAu(); var b = others.get(vector.requestedTimeUtc());
                var geo = b.minus(a);
                double value = switch (event.type()) {
                    case CLOSEST_APPROACH, FARTHEST_APPROACH -> a.distanceTo(b);
                    case OPPOSITION, CONJUNCTION -> EventGeometry.signedAngle(longitude(geo) - longitude(a.negate()));
                    case GREATEST_ELONGATION, TRANSIT -> geo.angleBetweenDeg(a.negate());
                    case STATIONARY, RETROGRADE_START, RETROGRADE_END -> longitude(geo);
                    case BRIGHTEST_APPROACH -> catalog.computeVisualMagnitude(second, b.magnitude(), geo.magnitude(),
                        b.negate().angleBetweenDeg(a.minus(b)));
                    default -> throw new JplHorizonsException("Unsupported geometry");
                };
                return new Frame(vector.requestedTimeUtc(), a, b, value);
            }).sorted(Comparator.comparing(Frame::time)).toList();
    }

    private void checkTransit(CatalogEvent event, List<Frame> frames, EventGeometry.Sample refined) {
        Frame frame = frames.stream().filter(value -> value.time().equals(refined.time())).findFirst()
            .orElseThrow(() -> new JplHorizonsException("Missing transit geometry"));
        double observerSun = frame.a().magnitude(), observerPlanet = frame.b().distanceTo(frame.a());
        double planetRadiusKm = target(event) == BodyId.MERCURY ? 2439.7 : 6051.8;
        double solarRadius = Math.toDegrees(Math.asin(695700.0 / (observerSun * EventCatalogService.AU_KM)));
        double planetRadius = Math.toDegrees(Math.asin(planetRadiusKm / (observerPlanet * EventCatalogService.AU_KM)));
        if (!(observerPlanet < observerSun) || !(refined.value() <= solarRadius + planetRadius)) {
            throw new JplHorizonsException("JPL geometry does not place the planet across the solar disk");
        }
    }
    private static BodyId target(CatalogEvent event) { return event.bodyA() == BodyId.EARTH ? event.bodyB() : event.bodyA(); }
    private static double longitude(Vector3Au vector) { return Math.toDegrees(Math.atan2(vector.y(), vector.x())); }
    private record Frame(Instant time, Vector3Au a, Vector3Au b, double value) {}
}

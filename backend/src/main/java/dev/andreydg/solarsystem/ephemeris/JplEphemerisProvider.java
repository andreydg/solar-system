package dev.andreydg.solarsystem.ephemeris;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.jpl.JplHorizonsClient;
import dev.andreydg.solarsystem.jpl.JplHorizonsException;
import dev.andreydg.solarsystem.jpl.JplVector;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class JplEphemerisProvider implements EphemerisProvider {
    private static final Duration BATCH_WINDOW = Duration.ofDays(365L * 50);

    private final JplHorizonsClient horizonsClient;
    private final ConcurrentMap<CacheKey, Vector3Au> cache = new ConcurrentHashMap<>();

    public JplEphemerisProvider(JplHorizonsClient horizonsClient) {
        this.horizonsClient = horizonsClient;
    }

    @Override
    public Vector3Au heliocentricPosition(BodyId body, Instant timeUtc) {
        if (!body.isSmallBody()) {
            throw new IllegalArgumentException("JPL ephemeris is only configured for small bodies: " + body);
        }

        CacheKey key = new CacheKey(body, timeUtc.truncatedTo(ChronoUnit.HOURS));
        return cache.computeIfAbsent(key, ignored -> horizonsClient.vector(body, timeUtc).positionAu());
    }

    public List<HeliocentricDistanceSample> heliocentricDistanceSamples(
        BodyId body,
        Instant from,
        Instant to,
        Duration step
    ) {
        return heliocentricTrajectory(body, from, to, step).stream()
            .map(sample -> new HeliocentricDistanceSample(
                sample.timeUtc(),
                sample.position().magnitude()
            ))
            .toList();
    }

    public List<TrajectorySample> heliocentricTrajectory(
        BodyId body,
        Instant from,
        Instant to,
        Duration step
    ) {
        if (!body.isSmallBody()) {
            throw new IllegalArgumentException("JPL ephemeris is only configured for small bodies: " + body);
        }

        List<TrajectorySample> samples = new ArrayList<>();
        Instant windowStart = from;

        while (!windowStart.isAfter(to)) {
            Instant windowEnd = windowStart.plus(BATCH_WINDOW);
            if (windowEnd.isAfter(to)) {
                windowEnd = to;
            }

            try {
                for (JplVector vector : horizonsClient.vectors(body, windowStart, windowEnd, formatStep(step))) {
                    Vector3Au position = vector.positionAu();
                    cache.put(
                        new CacheKey(body, vector.requestedTimeUtc().truncatedTo(ChronoUnit.HOURS)),
                        position
                    );
                    if (!samples.isEmpty()) {
                        TrajectorySample last = samples.getLast();
                        if (last.timeUtc().equals(vector.requestedTimeUtc())) {
                            continue;
                        }
                    }
                    samples.add(new TrajectorySample(vector.requestedTimeUtc(), position));
                }
            } catch (JplHorizonsException exception) {
                if (exception.getMessage().contains("No ephemeris")) {
                    break;
                }

                throw exception;
            }

            if (windowEnd.equals(to)) {
                break;
            }

            windowStart = windowEnd;
        }

        return samples;
    }

    private static String formatStep(Duration step) {
        if (step.toDays() >= 1) {
            return step.toDays() + " d";
        }

        if (step.toHours() >= 1) {
            return step.toHours() + " h";
        }

        return step.toMinutes() + " m";
    }

    public record HeliocentricDistanceSample(Instant timeUtc, double distanceAu) {
    }

    public record TrajectorySample(Instant timeUtc, Vector3Au position) {
    }

    @Override
    public String source() {
        return "JPL_HORIZONS";
    }

    private record CacheKey(BodyId body, Instant timeUtc) {
    }
}

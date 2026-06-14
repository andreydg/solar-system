package dev.andreydg.solarsystem.ephemeris;

import dev.andreydg.solarsystem.catalog.BodyId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class CompositeEphemerisProvider implements EphemerisProvider {
    private final Vsop87aEphemerisProvider vsop87aEphemerisProvider;
    private final JplEphemerisProvider jplEphemerisProvider;
    private final SmallBodyTrajectoryService trajectoryService;

    public CompositeEphemerisProvider(
        Vsop87aEphemerisProvider vsop87aEphemerisProvider,
        JplEphemerisProvider jplEphemerisProvider,
        SmallBodyTrajectoryService trajectoryService
    ) {
        this.vsop87aEphemerisProvider = vsop87aEphemerisProvider;
        this.jplEphemerisProvider = jplEphemerisProvider;
        this.trajectoryService = trajectoryService;
    }

    @Override
    public Vector3Au heliocentricPosition(BodyId body, Instant timeUtc) {
        if (body.isSmallBody()) {
            return trajectoryService.interpolate(body, timeUtc)
                .orElseGet(() -> jplEphemerisProvider.heliocentricPosition(body, timeUtc));
        }

        return vsop87aEphemerisProvider.heliocentricPosition(body, timeUtc);
    }

    @Override
    public String source() {
        return vsop87aEphemerisProvider.source();
    }

    public String sourceFor(BodyId body) {
        return body.isSmallBody() ? jplEphemerisProvider.source() : vsop87aEphemerisProvider.source();
    }

    public List<JplEphemerisProvider.HeliocentricDistanceSample> heliocentricDistanceSamples(
        BodyId body,
        Instant from,
        Instant to,
        Duration step
    ) {
        if (!body.isSmallBody()) {
            throw new IllegalArgumentException("Heliocentric distance samples are only configured for small bodies: " + body);
        }

        List<JplEphemerisProvider.TrajectorySample> trajectory = trajectoryService.getTrajectory(body);
        if (!trajectory.isEmpty()) {
            List<JplEphemerisProvider.HeliocentricDistanceSample> samples = new ArrayList<>();
            for (Instant time = from; !time.isAfter(to); time = time.plus(step)) {
                Instant sampleTime = time;
                trajectoryService.interpolate(body, sampleTime).ifPresent(position -> samples.add(
                    new JplEphemerisProvider.HeliocentricDistanceSample(sampleTime, position.magnitude())
                ));
            }

            if (!samples.isEmpty()) {
                return samples;
            }
        }

        return jplEphemerisProvider.heliocentricDistanceSamples(body, from, to, step);
    }
}

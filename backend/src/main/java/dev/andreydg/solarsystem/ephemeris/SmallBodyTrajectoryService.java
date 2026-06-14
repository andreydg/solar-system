package dev.andreydg.solarsystem.ephemeris;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.TrajectoryCacheProperties;
import dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class SmallBodyTrajectoryService {
    /** Target samples per half-orbit window; high count is fine once cached on disk. */
    public static final int ORBIT_SAMPLE_COUNT = 19_200;

    private static final Logger log = LoggerFactory.getLogger(SmallBodyTrajectoryService.class);

    private final JplEphemerisProvider jplEphemerisProvider;
    private final TrajectoryFileStore trajectoryFileStore;
    private final TrajectoryCacheProperties trajectoryCacheProperties;
    private final ConcurrentMap<BodyId, List<TrajectorySample>> cache = new ConcurrentHashMap<>();

    public SmallBodyTrajectoryService(
        JplEphemerisProvider jplEphemerisProvider,
        TrajectoryFileStore trajectoryFileStore,
        TrajectoryCacheProperties trajectoryCacheProperties
    ) {
        this.jplEphemerisProvider = jplEphemerisProvider;
        this.trajectoryFileStore = trajectoryFileStore;
        this.trajectoryCacheProperties = trajectoryCacheProperties;
    }

    public List<TrajectorySample> getTrajectory(BodyId body) {
        if (!body.isSmallBody()) {
            throw new IllegalArgumentException("Trajectory cache is only configured for small bodies: " + body);
        }

        List<TrajectorySample> cached = cache.get(body);
        if (cached != null) {
            return cached;
        }

        List<TrajectorySample> loaded = loadTrajectory(body);
        if (!loaded.isEmpty()) {
            cache.put(body, loaded);
        }

        return loaded;
    }

    public Optional<Vector3Au> interpolate(BodyId body, Instant timeUtc) {
        List<TrajectorySample> trajectory = cache.get(body);
        if (trajectory == null || trajectory.isEmpty()) {
            trajectory = getTrajectory(body);
            if (trajectory.isEmpty()) {
                return Optional.empty();
            }
        }

        Instant queryTime = resolveQueryTime(body, trajectory, timeUtc);
        return interpolateWithinRange(trajectory, queryTime);
    }

    public static Instant resolveQueryTime(BodyId body, List<TrajectorySample> trajectory, Instant timeUtc) {
        TrajectorySample first = trajectory.getFirst();
        TrajectorySample last = trajectory.getLast();

        if (!timeUtc.isBefore(first.timeUtc()) && !timeUtc.isAfter(last.timeUtc())) {
            return timeUtc;
        }

        long orbitDays = Math.max(1, Math.round(body.typicalOrbitDays()));
        long daysFromFirst = Duration.between(first.timeUtc(), timeUtc).toDays();
        long wrappedDays = Math.floorMod(daysFromFirst, orbitDays);
        Instant phasedTime = first.timeUtc().plus(Duration.ofDays(wrappedDays));

        if (!phasedTime.isAfter(last.timeUtc())) {
            return phasedTime;
        }

        long spanDays = Duration.between(first.timeUtc(), last.timeUtc()).toDays();
        long offsetDays = Math.floorMod(wrappedDays, spanDays + 1);
        return first.timeUtc().plus(Duration.ofDays(offsetDays));
    }

    // Package-private so unit tests in this package can exercise the interpolation algorithm directly.
    static Optional<Vector3Au> interpolateWithinRange(List<TrajectorySample> trajectory, Instant timeUtc) {
        if (trajectory.isEmpty()) {
            return Optional.empty();
        }
        if (trajectory.size() == 1) {
            return Optional.of(trajectory.getFirst().position());
        }

        Instant firstTime = trajectory.getFirst().timeUtc();
        Instant lastTime = trajectory.getLast().timeUtc();

        if (timeUtc.isBefore(firstTime)) {
            return Optional.of(trajectory.getFirst().position());
        }
        if (timeUtc.isAfter(lastTime)) {
            return Optional.of(trajectory.getLast().position());
        }

        // Binary search to find index such that trajectory[index].timeUtc <= timeUtc && timeUtc <= trajectory[index+1].timeUtc
        int low = 0;
        int high = trajectory.size() - 2;
        int index = 0;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            Instant midTime = trajectory.get(mid).timeUtc();
            if (!midTime.isAfter(timeUtc)) {
                index = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        TrajectorySample start = trajectory.get(index);
        TrajectorySample end = trajectory.get(index + 1);

        long startMillis = start.timeUtc().toEpochMilli();
        long endMillis = end.timeUtc().toEpochMilli();
        if (endMillis == startMillis) {
            return Optional.of(start.position());
        }

        double ratio = (timeUtc.toEpochMilli() - startMillis) / (double) (endMillis - startMillis);
        return Optional.of(lerp(start.position(), end.position(), ratio));
    }

    public boolean covers(BodyId body, Instant timeUtc) {
        List<TrajectorySample> trajectory = cache.get(body);
        if (trajectory == null || trajectory.isEmpty()) {
            return false;
        }

        return !timeUtc.isBefore(trajectory.getFirst().timeUtc())
            && !timeUtc.isAfter(trajectory.getLast().timeUtc());
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void prewarmTrajectories() {
        Arrays.stream(BodyId.values())
            .filter(BodyId::isSmallBody)
            .forEach(body -> {
                try {
                    if (!trajectoryCacheProperties.refreshFromJpl()) {
                        Thread.sleep(100);
                    } else {
                        Thread.sleep(1_500);
                    }
                    List<TrajectorySample> trajectory = getTrajectory(body);
                    log.info("Prewarmed trajectory for {} ({} samples)", body.apiValue(), trajectory.size());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException exception) {
                    log.warn("Failed to prewarm trajectory for {}: {}", body.apiValue(), exception.getMessage());
                }
            });
    }

    private List<TrajectorySample> loadTrajectory(BodyId body) {
        if (!trajectoryCacheProperties.refreshFromJpl()) {
            Optional<List<TrajectorySample>> fromFile = trajectoryFileStore.load(body);
            if (fromFile.isPresent()) {
                return fromFile.get();
            }
        }

        Instant epoch = body.trajectoryEpoch();
        double orbitDays = body.typicalOrbitDays();
        Duration halfOrbit = Duration.ofDays(Math.round(orbitDays / 2.0));
        Instant from = epoch.minus(halfOrbit);
        Instant to = epoch.plus(halfOrbit);

        Duration step = trajectoryStep(body, orbitDays);
        List<TrajectorySample> fromJpl = jplEphemerisProvider.heliocentricTrajectory(body, from, to, step);
        if (!fromJpl.isEmpty()) {
            trajectoryFileStore.save(body, fromJpl, from, to, step);
        }

        return fromJpl;
    }

    private static Duration trajectoryStep(BodyId body, double orbitDays) {
        long stepDays = Math.max(1, Math.round(orbitDays / ORBIT_SAMPLE_COUNT));

        return switch (body) {
            case HALLEY -> Duration.ofDays(Math.max(1, Math.min(2, stepDays)));
            case ENCKE -> Duration.ofDays(Math.max(1, Math.min(1, stepDays)));
            default -> Duration.ofDays(Math.max(1, Math.min(5, stepDays)));
        };
    }

    private static Vector3Au lerp(Vector3Au start, Vector3Au end, double ratio) {
        return new Vector3Au(
            start.x() + ratio * (end.x() - start.x()),
            start.y() + ratio * (end.y() - start.y()),
            start.z() + ratio * (end.z() - start.z())
        );
    }
}

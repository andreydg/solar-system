package dev.andreydg.solarsystem.ephemeris;

import tools.jackson.databind.json.JsonMapper;
import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.TrajectoryCacheProperties;
import dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TrajectoryFileStore {
    static final int FORMAT_VERSION = 2;

    private static final Logger log = LoggerFactory.getLogger(TrajectoryFileStore.class);

    private final TrajectoryCacheProperties properties;
    private final JsonMapper jsonMapper;

    public TrajectoryFileStore(TrajectoryCacheProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public Optional<List<TrajectorySample>> load(BodyId body) {
        Path path = pathFor(body);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }

        try {
            CachedTrajectoryFile cached = jsonMapper.readValue(path.toFile(), CachedTrajectoryFile.class);
            if (cached.formatVersion() != FORMAT_VERSION) {
                log.info("Ignoring stale trajectory file for {} (format {} != {})", body.apiValue(), cached.formatVersion(), FORMAT_VERSION);
                return Optional.empty();
            }

            if (!body.apiValue().equals(cached.body())) {
                log.warn("Trajectory file {} has mismatched body id {}", path, cached.body());
                return Optional.empty();
            }

            List<TrajectorySample> samples = cached.samples().stream()
                .map(sample -> new TrajectorySample(
                    Instant.parse(sample.timeUtc()),
                    new Vector3Au(sample.x(), sample.y(), sample.z())
                ))
                .toList();

            if (samples.isEmpty()) {
                return Optional.empty();
            }

            log.info("Loaded cached trajectory for {} from {} ({} samples)", body.apiValue(), path, samples.size());
            return Optional.of(samples);
        } catch (RuntimeException exception) {
            log.warn("Failed to read trajectory cache for {} from {}: {}", body.apiValue(), path, exception.getMessage());
            return Optional.empty();
        }
    }

    public void save(
        BodyId body,
        List<TrajectorySample> samples,
        Instant from,
        Instant to,
        Duration step
    ) {
        if (samples.isEmpty()) {
            return;
        }

        Path path = pathFor(body);
        CachedTrajectoryFile cached = new CachedTrajectoryFile(
            FORMAT_VERSION,
            body.apiValue(),
            Instant.now().toString(),
            from.toString(),
            to.toString(),
            step.toDays(),
            samples.stream()
                .map(sample -> new CachedTrajectoryFile.Sample(
                    sample.timeUtc().toString(),
                    sample.position().x(),
                    sample.position().y(),
                    sample.position().z()
                ))
                .toList()
        );

        try {
            Files.createDirectories(path.getParent());
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), cached);
            log.info("Saved trajectory cache for {} to {} ({} samples)", body.apiValue(), path, samples.size());
        } catch (Exception exception) {
            log.warn("Failed to write trajectory cache for {} to {}: {}", body.apiValue(), path, exception.getMessage());
        }
    }

    public Path pathFor(BodyId body) {
        return Path.of(properties.cacheDir()).resolve(body.apiValue() + ".json");
    }

    record CachedTrajectoryFile(
        int formatVersion,
        String body,
        String generatedAtUtc,
        String fromUtc,
        String toUtc,
        long stepDays,
        List<Sample> samples
    ) {
        record Sample(String timeUtc, double x, double y, double z) {
        }
    }
}

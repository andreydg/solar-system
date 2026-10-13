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
    static final int FORMAT_VERSION = 3;

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

            if (!validSamples(samples)) {
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
        if (!validSamples(samples)) {
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
            Path temporary = Files.createTempFile(path.getParent(), body.apiValue(), ".tmp");
            try {
                jsonMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), cached);
                Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            log.info("Saved trajectory cache for {} to {} ({} samples)", body.apiValue(), path, samples.size());
        } catch (Exception exception) {
            log.warn("Failed to write trajectory cache for {} to {}: {}", body.apiValue(), path, exception.getMessage());
        }
    }

    public List<TrajectorySample> loadBundled(BodyId body) {
        try (var stream = getClass().getResourceAsStream("/trajectories/" + body.apiValue() + "-v1.txt")) {
            if (stream == null) throw new IllegalStateException("Missing bundled trajectory for " + body);
            String response = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return parseBundled(body, response);
        } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot read bundled trajectory", exception); }
    }

    static List<TrajectorySample> parseBundled(BodyId body, String response) {
        String target = switch (body) {
            case CERES -> "1 Ceres (A801 AA)";
            case VESTA -> "4 Vesta (A807 FA)";
            case ENCKE -> "2P/Encke";
            case HALLEY -> "1P/Halley";
            default -> throw new IllegalArgumentException("No bundled trajectory for " + body);
        };
        int start = response.indexOf("$SOE");
        if (start < 0) throw new IllegalStateException("Missing trajectory data");
        String header = response.substring(0, start);
        for (String required : List.of(
            "^Target body name:\\s*" + java.util.regex.Pattern.quote(target) + "\\s+\\{",
            "^Center body name:\\s*Sun \\(10\\)",
            "^Center-site name:\\s*BODY CENTER",
            "^Output units\\s*:\\s*AU-D\\s*$",
            "^Calendar mode\\s*:\\s*Gregorian\\s*$",
            "^Output type\\s*:\\s*GEOMETRIC cartesian states\\s*$",
            "^Reference frame\\s*:\\s*Ecliptic of J2000\\.0\\s*$",
            "^Start time\\s*:[^\\r\\n]+ UT\\s*$",
            "^Stop  time\\s*:[^\\r\\n]+ UT\\s*$",
            "Calendar Date \\(UT \\)")) {
            if (!java.util.regex.Pattern.compile(required, java.util.regex.Pattern.MULTILINE).matcher(header).find())
                throw new IllegalStateException("Invalid bundled trajectory metadata for " + body);
        }
        var samples = dev.andreydg.solarsystem.jpl.JplHorizonsClient.parseVectors(body, response).stream()
            .map(vector -> new TrajectorySample(vector.requestedTimeUtc(), vector.positionAu())).toList();
        if (!validSamples(samples)) throw new IllegalStateException("Invalid bundled trajectory for " + body);
        return samples;
    }

    private static boolean validSamples(List<TrajectorySample> samples) {
        if (samples == null || samples.size() < 2) return false;
        Instant previous = null;
        for (var sample : samples) {
            if (sample.timeUtc() == null || sample.position() == null ||
                !Double.isFinite(sample.position().x()) || !Double.isFinite(sample.position().y()) ||
                !Double.isFinite(sample.position().z()) ||
                (previous != null && !sample.timeUtc().isAfter(previous))) return false;
            previous = sample.timeUtc();
        }
        return true;
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

package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.TrajectoryCacheProperties;
import dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample;
import dev.andreydg.solarsystem.ephemeris.TrajectoryFileStore;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrajectoryFileStoreTests {
    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void roundTripsTrajectorySamples() throws Exception {
        TrajectoryFileStore store = new TrajectoryFileStore(
            new TrajectoryCacheProperties(tempDir.toString(), false),
            JsonMapper.builder().build()
        );

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z");
        List<TrajectorySample> samples = List.of(
            new TrajectorySample(from, new Vector3Au(1.0, 2.0, 3.0)),
            new TrajectorySample(to, new Vector3Au(4.0, 5.0, 6.0))
        );

        store.save(BodyId.ENCKE, samples, from, to, Duration.ofDays(2));

        assertThat(Files.exists(store.pathFor(BodyId.ENCKE))).isTrue();

        List<TrajectorySample> loaded = store.load(BodyId.ENCKE).orElseThrow();
        assertThat(loaded).hasSize(2);
        assertThat(loaded.getFirst().position().x()).isEqualTo(1.0);
        assertThat(loaded.getLast().position().z()).isEqualTo(6.0);
    }
}

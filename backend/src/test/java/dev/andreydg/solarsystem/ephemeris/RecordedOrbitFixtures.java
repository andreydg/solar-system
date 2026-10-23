package dev.andreydg.solarsystem.ephemeris;

import dev.andreydg.solarsystem.catalog.BodyId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Recorded Horizons orbit rows; interpolation and phase wrapping are production code. */
public final class RecordedOrbitFixtures {
    private RecordedOrbitFixtures() {}
    public static CompositeEphemerisProvider provider() {
        var paths = new EnumMap<BodyId, List<JplEphemerisProvider.TrajectorySample>>(BodyId.class);
        for (BodyId body : List.of(BodyId.HALLEY, BodyId.ENCKE, BodyId.CERES, BodyId.VESTA)) {
            try (var input = Objects.requireNonNull(RecordedOrbitFixtures.class.getResourceAsStream(
                "/jpl/" + body.apiValue() + "-orbit.csv"))) {
                paths.put(body, new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().map(line -> {
                    String[] v = line.split(",");
                    return new JplEphemerisProvider.TrajectorySample(Instant.parse(v[0]),
                        new Vector3Au(Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3])));
                }).toList());
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
        var trajectories = new SmallBodyTrajectoryService(null, null, null) {
            @Override public List<JplEphemerisProvider.TrajectorySample> getTrajectory(BodyId body) {
                return Objects.requireNonNull(paths.get(body), body.name());
            }
        };
        return new CompositeEphemerisProvider(new KeplerianEphemerisProvider(), null, trajectories);
    }
}

package dev.andreydg.solarsystem;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.TrajectoryCacheProperties;
import dev.andreydg.solarsystem.ephemeris.*;
import dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample;
import dev.andreydg.solarsystem.jpl.JplHorizonsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Synthetic orbits exercise catalog plumbing, NOT scientific accuracy. No disk/network fallback. */
@TestConfiguration(proxyBeanMethods = false)
public class OfflineEphemerisConfiguration {
    @Bean @Primary
    JplHorizonsClient forbiddenNetworkClient() {
        var client = mock(JplHorizonsClient.class);
        when(client.vector(any(), any())).thenThrow(new AssertionError("Unexpected live JPL dependency"));
        when(client.vectors(any(), any(), any(), any())).thenThrow(new AssertionError("Unexpected live JPL dependency"));
        return client;
    }

    @Bean @Primary
    SmallBodyTrajectoryService syntheticTrajectories(JplEphemerisProvider provider,
        TrajectoryFileStore store, TrajectoryCacheProperties properties) {
        return new SmallBodyTrajectoryService(provider, store, properties) {
            private final ConcurrentHashMap<BodyId, List<TrajectorySample>> fixtures = new ConcurrentHashMap<>();
            @Override public void prewarmTrajectories() {}
            @Override public List<TrajectorySample> getTrajectory(BodyId body) {
                if (!body.isSmallBody()) return super.getTrajectory(body);
                return fixtures.computeIfAbsent(body, key -> {
                    var samples = new ArrayList<TrajectorySample>();
                    long period = Math.round(key.typicalOrbitDays());
                    Instant epoch = key.trajectoryEpoch();
                    for (long day = -period / 2; day <= period / 2; day++) {
                        double angle = day * 2 * Math.PI / period;
                        double radius = 2.5 * (1 - 0.3 * Math.cos(angle));
                        samples.add(new TrajectorySample(epoch.plus(Duration.ofDays(day)),
                            new Vector3Au(radius * Math.cos(angle), radius * Math.sin(angle), 0.01 * Math.sin(angle))));
                    }
                    return List.copyOf(samples);
                });
            }
        };
    }
}

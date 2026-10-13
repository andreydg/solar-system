package dev.andreydg.solarsystem.ephemeris;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.TrajectoryCacheProperties;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TrajectorySingleFlightTests {
    @Test void concurrentColdReadsShareOneDownloadAndFailuresHaveACooldown() throws Exception {
        var provider = mock(JplEphemerisProvider.class);
        var files = mock(TrajectoryFileStore.class);
        when(files.load(any())).thenReturn(Optional.empty());
        var service = new SmallBodyTrajectoryService(provider, files, new TrajectoryCacheProperties("/unused", false));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var samples = List.of(new JplEphemerisProvider.TrajectorySample(Instant.EPOCH, new Vector3Au(1, 0, 0)));
        when(provider.heliocentricTrajectory(eq(BodyId.CERES), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return samples;
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() ->
                assertThat(service.getTrajectory(BodyId.CERES)).isEqualTo(samples)));
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            for (var future : futures) future.get();
        }
        verify(provider, times(1)).heliocentricTrajectory(eq(BodyId.CERES), any(), any(), any());
        when(provider.heliocentricTrajectory(eq(BodyId.HALLEY), any(), any(), any())).thenThrow(new IllegalStateException("offline"));
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> service.getTrajectory(BodyId.HALLEY)).hasMessage("offline");
        verify(provider, times(1)).heliocentricTrajectory(eq(BodyId.HALLEY), any(), any(), any());
    }
}

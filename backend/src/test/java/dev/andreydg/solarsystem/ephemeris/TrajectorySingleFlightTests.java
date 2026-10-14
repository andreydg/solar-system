package dev.andreydg.solarsystem.ephemeris;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.TrajectoryCacheProperties;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TrajectorySingleFlightTests {
    @Test void fatalLoaderFailureReleasesAllWaiters() throws Exception {
        var provider = mock(JplEphemerisProvider.class);
        var files = mock(TrajectoryFileStore.class);
        when(files.load(any())).thenReturn(Optional.empty());
        var service = new SmallBodyTrajectoryService(provider, files, new TrajectoryCacheProperties("/unused", true));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fatal = new AssertionError("simulated fatal load");
        when(provider.heliocentricTrajectory(eq(BodyId.CERES), any(), any(), any())).thenAnswer(call -> {
            entered.countDown(); release.await(5, TimeUnit.SECONDS); throw fatal;
        });
        var ownerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var waiterFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread owner = Thread.ofVirtual().start(() -> { try { service.getTrajectory(BodyId.CERES); } catch (Throwable t) { ownerFailure.set(t); } });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread waiter = Thread.ofVirtual().start(() -> { try { service.getTrajectory(BodyId.CERES); } catch (Throwable t) { waiterFailure.set(t); } });
        try { org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> waiter.getState() == Thread.State.WAITING); }
        finally { release.countDown(); }
        owner.join(Duration.ofSeconds(2)); waiter.join(Duration.ofSeconds(2));
        assertThat(owner.isAlive()).isFalse(); assertThat(waiter.isAlive()).isFalse();
        assertThat(ownerFailure.get()).isSameAs(fatal); assertThat(waiterFailure.get()).isSameAs(fatal);
    }

    @Test void concurrentColdReadsShareOneDownloadAndFailuresHaveACooldown() throws Exception {
        var provider = mock(JplEphemerisProvider.class);
        var files = mock(TrajectoryFileStore.class);
        when(files.load(any())).thenReturn(Optional.empty());
        var service = new SmallBodyTrajectoryService(provider, files, new TrajectoryCacheProperties("/unused", true));
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

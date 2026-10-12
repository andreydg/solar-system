package dev.andreydg.solarsystem.ephemeris;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.TrajectoryCacheProperties;
import dev.andreydg.solarsystem.jpl.JplHorizonsClient;
import dev.andreydg.solarsystem.jpl.JplVector;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TrajectoryReliabilityTests {
    @TempDir java.nio.file.Path temporary;
    @Test void everyBundledBodyLoadsWithoutJplAndHasFiniteOrderedCoverage() {
        var client = mock(JplEphemerisProvider.class);
        var properties = new TrajectoryCacheProperties(temporary.toString(), false);
        var service = new SmallBodyTrajectoryService(client, new TrajectoryFileStore(properties, JsonMapper.builder().build()), properties);
        for (var body : List.of(BodyId.CERES, BodyId.VESTA, BodyId.ENCKE, BodyId.HALLEY)) {
            var samples = service.getTrajectory(body);
            assertThat(samples.size()).isGreaterThan(1000);
            assertThat(samples.getFirst().timeUtc()).isBefore(body.trajectoryEpoch());
            assertThat(samples.getLast().timeUtc()).isAfter(body.trajectoryEpoch());
            assertThat(service.interpolate(body, Instant.parse("2026-06-13T12:30:00Z"))).isPresent();
        }
        verifyNoInteractions(client);
    }
    @Test void concurrentColdRequestsShareOneLoadAndFailureCanRetry() throws Exception {
        var store = mock(TrajectoryFileStore.class);
        var properties = new TrajectoryCacheProperties(temporary.toString(), false);
        var samples = List.of(
            new JplEphemerisProvider.TrajectorySample(Instant.EPOCH, new Vector3Au(1, 0, 0)),
            new JplEphemerisProvider.TrajectorySample(Instant.EPOCH.plusSeconds(100), new Vector3Au(2, 0, 0)));
        when(store.loadBundled(BodyId.HALLEY)).thenThrow(new IllegalStateException("temporary")).thenReturn(samples);
        var service = new SmallBodyTrajectoryService(mock(JplEphemerisProvider.class), store, properties);
        assertThatThrownBy(() -> service.getTrajectory(BodyId.HALLEY)).isInstanceOf(IllegalStateException.class);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var requests = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> (Callable<Object>) () -> service.getTrajectory(BodyId.HALLEY)).toList();
            for (var result : pool.invokeAll(requests)) assertThat(result.get()).isEqualTo(samples);
        }
        verify(store, times(2)).loadBundled(BodyId.HALLEY); // one failure, one shared success
    }
    @Test void orbitWrappingPreservesSubDayMotion() {
        var start = Instant.parse("2024-01-01T00:00:00Z");
        var samples = List.of(new JplEphemerisProvider.TrajectorySample(start, new Vector3Au(1,0,0)),
            new JplEphemerisProvider.TrajectorySample(start.plusSeconds(1204L*86400), new Vector3Au(2,0,0)));
        var later = start.plusSeconds(1205L*86400 + 12345);
        assertThat(SmallBodyTrajectoryService.resolveQueryTime(BodyId.ENCKE, samples, later))
            .isEqualTo(start.plusSeconds(86400 + 12345));
    }
    @Test void pointCacheIsBoundedAndDoesNotAliasDifferentTimesWithinAnHour() {
        var client = mock(JplHorizonsClient.class);
        when(client.vector(eq(BodyId.HALLEY), any())).thenAnswer(call ->
            new JplVector(BodyId.HALLEY, call.getArgument(1), new Vector3Au(1,2,3), "test", "test"));
        var provider = new JplEphemerisProvider(client);
        for (int i=0; i<4100; i++) provider.heliocentricPosition(BodyId.HALLEY, Instant.EPOCH.plusSeconds(i));
        assertThat(provider.cachedPointCount()).isEqualTo(JplEphemerisProvider.MAX_POINT_CACHE);
        verify(client, times(4100)).vector(eq(BodyId.HALLEY), any());
    }
}

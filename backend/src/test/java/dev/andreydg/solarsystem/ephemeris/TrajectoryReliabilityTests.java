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
    @Test void corruptCoordinatesNameTheirSnapshotAndRetainTheParserCause() {
        for (BodyId body : List.of(BodyId.CERES, BodyId.VESTA, BodyId.ENCKE, BodyId.HALLEY)) {
            String response = TrajectoryFileStore.readBundled(body);
            String row = response.substring(response.indexOf("$SOE") + 5).lines()
                .filter(line -> !line.isBlank()).findFirst().orElseThrow();
            String coordinate = row.split(",")[2];
            String corrupt = response.replace(row, row.replace(coordinate, " 1.23x4E+00 "));
            assertThatThrownBy(() -> TrajectoryFileStore.parseBundled(body, corrupt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid bundled trajectory for " + body)
                .hasCauseInstanceOf(NumberFormatException.class);
        }
    }

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
        var clock = mock(java.time.Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        var service = new SmallBodyTrajectoryService(mock(JplEphemerisProvider.class), store, properties, clock);
        assertThatThrownBy(() -> service.getTrajectory(BodyId.HALLEY)).isInstanceOf(IllegalStateException.class);
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(11));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var requests = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> (Callable<Object>) () -> service.getTrajectory(BodyId.HALLEY)).toList();
            for (var result : pool.invokeAll(requests)) assertThat(result.get()).isEqualTo(samples);
        }
        verify(store, times(2)).loadBundled(BodyId.HALLEY); // one failure, one shared success
    }
    @Test void wrongSnapshotIdentityFrameUnitsAndTimescaleAreRejected() throws Exception {
        for (var body : List.of(BodyId.CERES, BodyId.VESTA, BodyId.ENCKE, BodyId.HALLEY)) {
            try (var input = getClass().getResourceAsStream("/trajectories/" + body.apiValue() + "-v1.txt")) {
                String raw = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String corrupted : List.of(
                    raw.replaceFirst("Target body name: [^\\r\\n]+", "Target body name: wrong"),
                    raw.replace("Sun (10)", "Earth (399)"),
                    raw.replace("BODY CENTER", "SURFACE OBSERVER"),
                    raw.replace("AU-D", "KM-S"),
                    raw.replace("Gregorian", "Mixed"),
                    raw.replace("GEOMETRIC cartesian states", "APPARENT cartesian states"),
                    raw.replace("Ecliptic of J2000.0", "ICRF"),
                    raw.replace("Calendar Date (UT )", "Calendar Date (TDB)"),
                    raw.replace(" UT ", " TDB "))) {
                    assertThatThrownBy(() -> TrajectoryFileStore.parseBundled(body, corrupted))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("metadata");
                }
            }
        }
    }

    @Test void slowPointMissDoesNotBlockCachedHitsOrOtherKeysAndIsSingleFlight() throws Exception {
        var client = mock(JplHorizonsClient.class);
        when(client.vector(any(), any())).thenAnswer(call ->
            new JplVector(call.getArgument(0), call.getArgument(1), new Vector3Au(1,2,3), "test", "test"));
        var provider = new JplEphemerisProvider(client);
        provider.heliocentricPosition(BodyId.HALLEY, Instant.EPOCH);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(client.vector(BodyId.CERES, Instant.EPOCH)).thenAnswer(call -> {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new JplVector(BodyId.CERES, Instant.EPOCH, new Vector3Au(1,2,3), "", "");
        });
        try (var pool = Executors.newFixedThreadPool(4)) {
            var first = pool.submit(() -> provider.heliocentricPosition(BodyId.CERES, Instant.EPOCH));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var duplicate = pool.submit(() -> provider.heliocentricPosition(BodyId.CERES, Instant.EPOCH));
                assertThat(pool.submit(() -> provider.heliocentricPosition(BodyId.HALLEY, Instant.EPOCH))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
                assertThat(pool.submit(() -> provider.heliocentricPosition(BodyId.VESTA, Instant.EPOCH))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
                release.countDown();
                assertThat(duplicate.get()).isEqualTo(first.get());
            } finally { release.countDown(); }
        }
        verify(client, times(1)).vector(BodyId.CERES, Instant.EPOCH);
    }

    @Test void bundledSnapshotsAreValidatedDuringBeanConstructionAndReadOnlyOnce() {
        var reads = new java.util.EnumMap<BodyId, Integer>(BodyId.class);
        var properties = new TrajectoryCacheProperties(temporary.toString(), false);
        var store = new TrajectoryFileStore(properties, JsonMapper.builder().build(), body -> {
            reads.merge(body, 1, Integer::sum);
            return TrajectoryFileStore.readBundled(body);
        });
        for (var body : List.of(BodyId.CERES, BodyId.VESTA, BodyId.ENCKE, BodyId.HALLEY)) {
            assertThat(reads.get(body)).isEqualTo(1);
            assertThat(store.loadBundled(body)).isSameAs(store.loadBundled(body));
            assertThatThrownBy(() -> new TrajectoryFileStore(properties, JsonMapper.builder().build(),
                id -> TrajectoryFileStore.readBundled(id).replace(id == body ? "AU-D" : "__absent__", "KM-S")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("metadata");
        }
        assertThat(reads.values()).containsOnly(1);
    }

    @Test void trajectoryMissDoesNotLockOutCachedHitsOrAnotherBody() throws Exception {
        var provider = mock(JplEphemerisProvider.class);
        var files = mock(TrajectoryFileStore.class);
        when(files.load(any())).thenReturn(java.util.Optional.empty());
        var samples = List.of(
            new JplEphemerisProvider.TrajectorySample(Instant.EPOCH, new Vector3Au(1, 0, 0)),
            new JplEphemerisProvider.TrajectorySample(Instant.EPOCH.plusSeconds(100), new Vector3Au(2, 0, 0)));
        when(provider.heliocentricTrajectory(any(), any(), any(), any())).thenReturn(samples);
        var service = new SmallBodyTrajectoryService(provider, files, new TrajectoryCacheProperties(temporary.toString(), true));
        service.getTrajectory(BodyId.HALLEY);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(provider.heliocentricTrajectory(eq(BodyId.CERES), any(), any(), any())).thenAnswer(call -> {
            entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); return samples;
        });
        try (var pool = Executors.newFixedThreadPool(3)) {
            var first = pool.submit(() -> service.getTrajectory(BodyId.CERES));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(pool.submit(() -> service.getTrajectory(BodyId.HALLEY)).get(1, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(samples);
                assertThat(pool.submit(() -> service.getTrajectory(BodyId.VESTA)).get(1, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(samples);
            } finally { release.countDown(); }
            assertThat(first.get()).isEqualTo(samples);
        }
    }

    @Test void fatalPointOwnerFailureDoesNotStrandWaiters() throws Exception {
        var client = mock(JplHorizonsClient.class);
        var provider = new JplEphemerisProvider(client);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var fatal = new AssertionError("simulated fatal load");
        when(client.vector(BodyId.CERES, Instant.EPOCH)).thenAnswer(call -> {
            entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); throw fatal;
        });
        var ownerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var waiterFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread owner = Thread.ofVirtual().start(() -> { try { provider.heliocentricPosition(BodyId.CERES, Instant.EPOCH); } catch (Throwable t) { ownerFailure.set(t); } });
        assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread waiter = Thread.ofVirtual().start(() -> { try { provider.heliocentricPosition(BodyId.CERES, Instant.EPOCH); } catch (Throwable t) { waiterFailure.set(t); } });
        try { org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).until(() -> waiter.getState() == Thread.State.WAITING); }
        finally { release.countDown(); }
        owner.join(java.time.Duration.ofSeconds(2)); waiter.join(java.time.Duration.ofSeconds(2));
        assertThat(owner.isAlive()).isFalse(); assertThat(waiter.isAlive()).isFalse();
        assertThat(ownerFailure.get()).isSameAs(fatal); assertThat(waiterFailure.get()).isSameAs(fatal);
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

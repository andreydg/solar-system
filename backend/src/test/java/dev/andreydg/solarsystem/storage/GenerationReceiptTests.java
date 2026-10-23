package dev.andreydg.solarsystem.storage;

import static org.assertj.core.api.Assertions.*;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.ephemeris.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class GenerationReceiptTests {
    @Test void repeatedGenerationDoesNotResetAnExistingJob() {
        var repository = new InMemoryEventCatalogRepository();
        var provider = new CompositeEphemerisProvider(new KeplerianEphemerisProvider(), null, null);
        var service = new EventCatalogService(provider, repository);
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var to = Instant.parse("2051-01-01T00:00:00Z");
        var original = service.generate(EventType.STATIONARY, BodyId.EARTH, BodyId.MERCURY, from, to);
        assertThat(original).hasSizeGreaterThan(100);
        var now = Instant.now();
        var lease = repository.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        var jobBefore = repository.inspectJob(lease.eventId()).orElseThrow();
        assertThat(jobBefore.attempts()).isEqualTo(1);
        assertThat(jobBefore.token()).isEqualTo(lease.token());
        for (int i = 0; i < 3; i++) {
            assertThat(service.generate(EventType.STATIONARY, BodyId.EARTH, BodyId.MERCURY, from, to)).isEqualTo(original);
        }
        assertThat(repository.inspectJob(lease.eventId())).contains(jobBefore);
        var claimedEvent = repository.findById(lease.eventId()).orElseThrow();
        assertThat(repository.complete(lease, claimedEvent, now)).isTrue();
    }

    @Test void concurrentCreationReceiptsNeverReportTheSameIdTwice() throws Exception {
        var repository = new InMemoryEventCatalogRepository();
        // Deterministically expose a check-then-put mutant: every absent read waits
        // until all contenders have observed absence. Atomic putIfAbsent never reads here.
        var absentReads = new java.util.concurrent.CyclicBarrier(8);
        var controlled = new java.util.concurrent.ConcurrentHashMap<String, CatalogEvent>() {
            @Override public CatalogEvent get(Object key) {
                var value = super.get(key);
                if (value == null) try { absentReads.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                return value;
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(repository, "events", controlled);
        var time = Instant.parse("2027-01-01T00:00:00Z");
        var event = new CatalogEvent("one", EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS,
            time, null, 180.0, null, "TEST", null, null, null, null, null, ValidationStatus.PENDING,
            time.minusSeconds(100), time.plusSeconds(100), time, null, null, null);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<EventCatalogRepository.WriteResult>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> repository.createMissing(List.of(event)));
            int created = 0;
            for (var future : executor.invokeAll(tasks)) {
                assertThat(future.get().stored()).containsExactly(event);
                created += future.get().created().size();
            }
            assertThat(created).isEqualTo(1);
        }
    }
}

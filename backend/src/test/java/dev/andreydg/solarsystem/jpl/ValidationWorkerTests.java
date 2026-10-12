package dev.andreydg.solarsystem.jpl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationWorkerTests {
    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private final InMemoryEventCatalogRepository repository = new InMemoryEventCatalogRepository();
    private final EventCatalogService catalog = new EventCatalogService(mock(CompositeEphemerisProvider.class), repository);
    private final JplValidationService validation = mock(JplValidationService.class);
    private ValidationWorker worker(Instant time, boolean enabled) {
        return new ValidationWorker(repository, catalog, validation, new JplProperties("unused", enabled, 2, 3, 1), Clock.fixed(time, ZoneOffset.UTC));
    }
    @Test void transientFailureRetriesWithoutAUserReadingTheEvent() {
        var event = event();
        repository.upsertAll(List.of(event));
        when(validation.validateEvent(event.id())).thenThrow(new JplHorizonsException("timeout", true))
            .thenReturn(event.validated(NOW, 0.5, null, null, 0.0, "fixture"));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("retry-scheduled");
        assertThat(repository.findById(event.id()).orElseThrow().validationStatus()).isEqualTo(ValidationStatus.PENDING);
        assertThat(worker(NOW.plusSeconds(59), true).drainOne()).isEqualTo("idle-or-busy");
        assertThat(worker(NOW.plusSeconds(60), true).drainOne()).isEqualTo("validated");
        assertThat(repository.findById(event.id()).orElseThrow().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
    }
    @Test void permanentFailureAndExhaustedRetriesAreTerminalUntilExplicitlyRequeued() {
        repository.upsertAll(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new JplHorizonsException("bad geometry"));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("failed");
        catalog.query(EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS, NOW.minusSeconds(10), NOW.plusSeconds(10));
        assertThat(worker(NOW.plusSeconds(1000), true).drainOne()).isEqualTo("idle-or-busy");
        repository.enqueue("event", NOW);
        doThrow(new JplHorizonsException("outage", true)).when(validation).validateEvent("event");
        Instant time = NOW;
        for (int i = 1; i <= 5; i++) {
            assertThat(worker(time, true).drainOne()).isEqualTo(i == 5 ? "failed" : "retry-scheduled");
            time = time.plusSeconds(60L << (i - 1));
        }
        assertThat(worker(time.plusSeconds(10000), true).drainOne()).isEqualTo("idle-or-busy");
    }
    @Test void abandonedLeaseIsRecoveredAndOldWorkerCannotOverwriteNewResult() {
        var event = event();
        repository.upsertAll(List.of(event));
        var old = repository.claim(NOW, Duration.ofSeconds(10)).orElseThrow();
        assertThat(repository.claim(NOW.plusSeconds(9), Duration.ofSeconds(10))).isEmpty();
        var replacement = repository.claim(NOW.plusSeconds(10), Duration.ofSeconds(10)).orElseThrow();
        assertThat(repository.complete(old, event.failed("stale"), NOW.plusSeconds(11))).isFalse();
        assertThat(repository.complete(replacement, event.validated(NOW, 0.5, null, null, 0.0, "new"), NOW.plusSeconds(11))).isTrue();
        assertThat(repository.complete(old, event.failed("stale"), NOW.plusSeconds(12))).isFalse();
    }
    @Test void duplicateGenerationDoesNotResetCompletedValidation() {
        var event = event(); repository.upsertAll(List.of(event));
        var lease = repository.claim(NOW, Duration.ofMinutes(1)).orElseThrow();
        repository.complete(lease, event.validated(NOW, 0.5, null, null, 0.0, "valid"), NOW);
        repository.upsertAll(List.of(event));
        assertThat(repository.findById(event.id()).orElseThrow().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(repository.claim(NOW, Duration.ofMinutes(1))).isEmpty();
    }
    @Test void operatorPauseLeavesDurableJobsUnclaimed() {
        repository.upsertAll(List.of(event()));
        assertThat(worker(NOW, false).drainOne()).isEqualTo("paused");
        verifyNoInteractions(validation);
        assertThat(repository.claim(NOW, Duration.ofMinutes(1))).isPresent();
    }
    @Test void orphanedJobCannotOccupyTheWorkerForever() {
        repository.upsertAll(List.of(event()));
        var missingCatalog = mock(EventCatalogService.class);
        when(missingCatalog.findById("event")).thenReturn(java.util.Optional.empty());
        var worker = new ValidationWorker(repository, missingCatalog, validation,
            new JplProperties("unused", true, 2, 3, 1), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(worker.drainOne()).isEqualTo("failed");
        assertThat(worker.drainOne()).isEqualTo("idle-or-busy");
        verifyNoInteractions(validation);
    }
    private CatalogEvent event() {
        return new CatalogEvent("event", EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS,
            NOW, 0.6, null, null, "TEST", null, null, null, null, null, ValidationStatus.PENDING,
            NOW.minusSeconds(100), NOW.plusSeconds(100), NOW, null, null, null);
    }
}

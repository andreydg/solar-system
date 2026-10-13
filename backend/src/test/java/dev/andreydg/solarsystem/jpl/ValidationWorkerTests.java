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
        assertThat(worker(NOW.plusSeconds(299), true).drainOne()).isEqualTo("idle-or-busy");
        assertThat(worker(NOW.plusSeconds(300), true).drainOne()).isEqualTo("validated");
        assertThat(repository.findById(event.id()).orElseThrow().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
    }
    @Test void permanentFailureAndExhaustedRetriesAreTerminalUntilExplicitlyRequeued() {
        repository.upsertAll(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new JplHorizonsException("bad geometry"));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("failed");
        catalog.search(new CatalogQuery(EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS, NOW.minusSeconds(10), NOW.plusSeconds(10), false, false, 10, null));
        assertThat(worker(NOW.plusSeconds(1000), true).drainOne()).isEqualTo("idle-or-busy");
        repository.enqueue("event", NOW);
        doThrow(new JplHorizonsException("outage", true)).when(validation).validateEvent("event");
        Instant time = NOW;
        for (int i = 1; i <= ValidationWorker.MAX_ATTEMPTS; i++) {
            assertThat(worker(time, true).drainOne()).isEqualTo(i == ValidationWorker.MAX_ATTEMPTS ? "failed" : "retry-scheduled");
            time = time.plus(ValidationWorker.retryDelay(i));
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
    @Test void explicitRevalidationPreservesAcceptedValuesEvenWhenItFails() {
        var accepted = event().validated(NOW, 0.5, null, null, 0.1, "last accepted");
        repository.upsert(accepted);
        repository.enqueue(accepted.id(), NOW);
        assertThat(repository.findById(accepted.id())).contains(accepted);
        when(validation.validateEvent(accepted.id())).thenThrow(new JplHorizonsException("new check rejected"));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("failed");
        verify(validation).validateEvent(accepted.id());
        assertThat(repository.findById(accepted.id())).contains(accepted);
        assertThat(worker(NOW, true).drainOne()).isEqualTo("idle-or-busy");
    }

    @Test void batchContinuesPastAnUnreadableEventAndChecksAttemptBudgetBeforeReading() {
        var jobs = mock(ValidationJobs.class);
        var service = mock(EventCatalogService.class);
        var poison = new ValidationJobs.Lease("poison", "a", 7);
        var exhausted = new ValidationJobs.Lease("exhausted", "b", ValidationWorker.MAX_ATTEMPTS + 1);
        var good = new ValidationJobs.Lease("event", "c", 1);
        when(jobs.claim(any(), any())).thenReturn(java.util.Optional.of(poison), java.util.Optional.of(exhausted),
            java.util.Optional.of(good), java.util.Optional.empty());
        when(jobs.abandon(any(), anyString(), any())).thenReturn(true);
        when(service.findById("poison")).thenThrow(new java.time.format.DateTimeParseException("invalid", "bad", 0));
        when(service.findById("event")).thenReturn(java.util.Optional.of(event()));
        when(validation.validateEvent("event")).thenReturn(event().validated(NOW, 0.5, null, null, 0.0, "ok"));
        when(jobs.complete(any(), any(), any())).thenReturn(true);
        var worker = new ValidationWorker(jobs, service, validation, new JplProperties("unused", true, 2, 3, 1), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(worker.drainBatch()).isEqualTo(new ValidationWorker.DrainResult("idle-or-busy", 3));
        verify(service, never()).findById("exhausted");
        verify(validation).validateEvent("event");
    }

    @Test void failedCommitNeverTurnsAnAcceptedCalculationIntoScientificRejection() {
        var jobs = mock(ValidationJobs.class);
        repository.upsert(event());
        var lease = new ValidationJobs.Lease("event", "fence", 1);
        when(jobs.claim(any(), any())).thenReturn(java.util.Optional.of(lease));
        var accepted = event().validated(NOW, 0.5, null, null, 0.0, "accepted");
        when(validation.validateEvent("event")).thenReturn(accepted);
        when(jobs.complete(any(), any(), any())).thenThrow(new dev.andreydg.solarsystem.storage.EventCatalogStorageException("offline", new RuntimeException()));
        when(jobs.retry(any(), any(), anyString(), any())).thenReturn(true);
        var worker = new ValidationWorker(jobs, catalog, validation, new JplProperties("unused", true, 2, 3, 1), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(worker.drainOne()).isEqualTo("retry-scheduled");
        verify(jobs).complete(lease, accepted, NOW);
        verify(jobs, never()).abandon(any(), anyString(), any());
        assertThat(repository.findById("event").orElseThrow().validationStatus()).isEqualTo(ValidationStatus.PENDING);
    }

    @Test void deterministicProgrammingFailuresAreNotRetriedAsJplOutages() {
        repository.upsertAll(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new IllegalArgumentException("invalid geometry input"));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("failed");
        assertThat(worker(NOW.plusSeconds(10000), true).drainOne()).isEqualTo("idle-or-busy");
    }

    @Test void exhaustedJobsCanBeExplicitlyRecoveredAndBackoffSpansALongOutage() {
        repository.upsertAll(List.of(event()));
        var lease = repository.claim(NOW, Duration.ofMinutes(10)).orElseThrow();
        repository.abandon(lease, "Retry budget exhausted", NOW);
        assertThat(repository.requeueExhausted(null, 1, NOW).queued()).isEqualTo(1);
        assertThat(repository.claim(NOW, Duration.ofMinutes(10))).isPresent();
        long seconds = java.util.stream.IntStream.range(1, ValidationWorker.MAX_ATTEMPTS)
            .mapToLong(attempt -> ValidationWorker.retryDelay(attempt).toSeconds()).sum();
        assertThat(seconds).isGreaterThan(Duration.ofHours(24).toSeconds());
        try (var ignored = ValidationBudget.start(Duration.ZERO)) {
            assertThatThrownBy(ValidationBudget::check).isInstanceOf(JplHorizonsException.class);
        }
        assertThatCode(ValidationBudget::check).doesNotThrowAnyException();
    }

    @Test void unexpectedCompletionFailureReleasesTheLeaseWithoutChangingTheEvent() {
        repository.upsertAll(List.of(event()));
        var jobs = spy(repository);
        when(validation.validateEvent("event")).thenReturn(event().validated(NOW, 0.5, null, null, 0.0, "accepted"));
        doThrow(new IllegalStateException("unexpected commit failure")).when(jobs).complete(any(), any(), any());
        var worker = new ValidationWorker(jobs, catalog, validation,
            new JplProperties("unused", true, 2, 3, 1), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(worker.drainOne()).isEqualTo("failed");
        verify(jobs).abandon(any(), contains("Unexpected worker failure"), eq(NOW));
        assertThat(repository.findById("event")).contains(event());
        repository.enqueue("event", NOW);
        assertThat(repository.claim(NOW, Duration.ofMinutes(10))).isPresent();
    }

    private CatalogEvent event() {
        return new CatalogEvent("event", EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS,
            NOW, 0.6, null, null, "TEST", null, null, null, null, null, ValidationStatus.PENDING,
            NOW.minusSeconds(100), NOW.plusSeconds(100), NOW, null, null, null);
    }
}

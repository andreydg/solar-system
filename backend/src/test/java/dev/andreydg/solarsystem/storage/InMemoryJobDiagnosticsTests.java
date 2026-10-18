package dev.andreydg.solarsystem.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider;
import dev.andreydg.solarsystem.jpl.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class InMemoryJobDiagnosticsTests {
    @org.junit.jupiter.api.Test void successfulRetryClearsThePreviousPrivateError() {
        var repository = new InMemoryEventCatalogRepository();
        var event = event("retry-success");
        repository.createMissing(List.of(event));
        var now = Instant.now();
        var lease = repository.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(repository.retry(lease, now, "temporary", now)).isTrue();
        assertThat(repository.inspectJob(event.id()).orElseThrow().lastError()).isEqualTo("temporary");
        var retry = repository.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(repository.complete(retry, event.validated(now, 0.5, null, null, 0.0, "ok"), now)).isTrue();
        assertThat(repository.inspectJob(event.id()).orElseThrow().lastError()).isNull();
    }

    private final InMemoryEventCatalogRepository repository = new InMemoryEventCatalogRepository();
    private final JplValidationService validation = mock(JplValidationService.class);
    private EventCatalogService catalog() { return new EventCatalogService(mock(CompositeEphemerisProvider.class), repository); }
    private ValidationWorker worker(ValidationJobs jobs, EventCatalogService catalog) {
        return new ValidationWorker(jobs, catalog, validation, new JplProperties("unused", true, 2, 3, 1));
    }
    private CatalogEvent event(String id) {
        var time = Instant.now();
        return new CatalogEvent(id, EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS, time, null, 180.0, null,
            "TEST", null, null, null, null, null, ValidationStatus.PENDING, time.minusSeconds(100), time.plusSeconds(100),
            time, null, null, null);
    }

    @Test void workerRetainsCappedPrivateDiagnosticsForTransientAndPermanentFailures(
            org.springframework.boot.test.system.CapturedOutput output) {
        for (boolean transientFailure : List.of(true, false)) {
            String id = transientFailure ? "transient" : "permanent";
            repository.createMissing(List.of(event(id)));
            var failure = new JplHorizonsException("HTTP 503 private-token-" + "x".repeat(9000), transientFailure);
            when(validation.validateEvent(id)).thenThrow(failure);
            assertThat(worker(repository, catalog()).drainOne()).isEqualTo(transientFailure ? "retry-scheduled" : "failed");
            assertThat(repository.inspectJob(id).orElseThrow().lastError())
                .isEqualTo(failure.toString().substring(0, 8000)).hasSize(8000);
            assertThat(String.valueOf(repository.findById(id).orElseThrow().jplRawSummary())).doesNotContain("private-token");
        }
        assertThat(output.getAll()).contains("JplHorizonsException: HTTP 503 private-token-");
    }

    @Test void privateDiagnosticsAreCappedAtEveryMemoryStoreBoundary() {
        var now = Instant.now();
        String reason = "private:" + "x".repeat(9000);
        for (String mode : List.of("complete", "retry", "abandon")) {
            var event = event(mode);
            repository.createMissing(List.of(event));
            var lease = repository.claim(now, Duration.ofMinutes(10)).orElseThrow();
            boolean finished = switch (mode) {
                case "complete" -> repository.complete(lease, event.failed("safe"), now, reason);
                case "retry" -> repository.retry(lease, now.plusSeconds(100), reason, now);
                default -> repository.abandon(lease, reason, now);
            };
            assertThat(finished).isTrue();
            assertThat(repository.inspectJob(mode).orElseThrow().lastError()).hasSize(8000).isEqualTo(reason.substring(0, 8000));
        }
    }

    @Test void unreadableEventAndUnexpectedCompletionKeepDistinctPrivateCauses() {
        repository.createMissing(List.of(event("unreadable")));
        var unreadable = mock(EventCatalogService.class);
        when(unreadable.findById("unreadable")).thenThrow(new IllegalStateException("broken serialized time"));
        assertThat(worker(repository, unreadable).drainOne()).isEqualTo("failed");
        assertThat(repository.inspectJob("unreadable").orElseThrow().lastError())
            .isEqualTo("Unreadable catalog event: java.lang.IllegalStateException: broken serialized time");

        var event = event("commit");
        repository.createMissing(List.of(event));
        var jobs = spy(repository);
        when(validation.validateEvent("commit")).thenReturn(event);
        doThrow(new IllegalStateException("broken result writer")).when(jobs).complete(any(), any(), any());
        assertThat(worker(jobs, catalog()).drainOne()).isEqualTo("failed");
        assertThat(jobs.inspectJob("commit").orElseThrow().lastError())
            .isEqualTo("Unexpected worker failure: java.lang.IllegalStateException: broken result writer");
    }

    @Test void missingEventRecoveryClearsTheOrphanAndContinuesTheNextPage() {
        var now = Instant.now();
        for (String id : List.of("a-orphan", "b-good")) {
            repository.createMissing(List.of(event(id)));
            var lease = repository.claim(now, Duration.ofMinutes(10)).orElseThrow();
            repository.abandon(lease, "Retry budget exhausted", now);
        }
        @SuppressWarnings("unchecked")
        var events = (Map<String, CatalogEvent>) org.springframework.test.util.ReflectionTestUtils.getField(repository, "events");
        events.remove("a-orphan");
        assertThat(repository.requeueExhausted(null, 1, now)).isEqualTo(new ValidationJobs.RecoveryPage(0, "a-orphan"));
        assertThat(repository.requeueExhausted(null, 1, now)).isEqualTo(new ValidationJobs.RecoveryPage(1, null));
        assertThat(repository.inspectJob("a-orphan").orElseThrow().lastError()).contains("Catalog event missing");
        assertThat(repository.claim(now, Duration.ofMinutes(10)).orElseThrow().eventId()).isEqualTo("b-good");
    }
}

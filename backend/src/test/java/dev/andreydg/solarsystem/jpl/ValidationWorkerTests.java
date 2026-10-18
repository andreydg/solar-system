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

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
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
        repository.createMissing(List.of(event));
        when(validation.validateEvent(event.id())).thenThrow(new JplHorizonsException("timeout", true))
            .thenReturn(event.validated(NOW, 0.5, null, null, 0.0, "fixture"));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("retry-scheduled");
        assertThat(repository.findById(event.id()).orElseThrow().validationStatus()).isEqualTo(ValidationStatus.PENDING);
        assertThat(worker(NOW.plusSeconds(299), true).drainOne()).isEqualTo("idle-or-busy");
        assertThat(worker(NOW.plusSeconds(300), true).drainOne()).isEqualTo("validated");
        assertThat(repository.findById(event.id()).orElseThrow().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
    }
    @Test void permanentFailureAndExhaustedRetriesAreTerminalUntilExplicitlyRequeued() {
        repository.createMissing(List.of(event()));
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
        repository.createMissing(List.of(event));
        var old = repository.claim(NOW, Duration.ofSeconds(10)).orElseThrow();
        assertThat(repository.claim(NOW.plusSeconds(9), Duration.ofSeconds(10))).isEmpty();
        var replacement = repository.claim(NOW.plusSeconds(10), Duration.ofSeconds(10)).orElseThrow();
        assertThat(repository.complete(old, event.failed("stale"), NOW.plusSeconds(11))).isFalse();
        assertThat(repository.complete(replacement, event.validated(NOW, 0.5, null, null, 0.0, "new"), NOW.plusSeconds(11))).isTrue();
        assertThat(repository.complete(old, event.failed("stale"), NOW.plusSeconds(12))).isFalse();
    }
    @Test void duplicateGenerationDoesNotResetCompletedValidation() {
        var event = event(); repository.createMissing(List.of(event));
        var lease = repository.claim(NOW, Duration.ofMinutes(1)).orElseThrow();
        repository.complete(lease, event.validated(NOW, 0.5, null, null, 0.0, "valid"), NOW);
        repository.createMissing(List.of(event));
        assertThat(repository.findById(event.id()).orElseThrow().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(repository.claim(NOW, Duration.ofMinutes(1))).isEmpty();
    }
    @Test void operatorPauseLeavesDurableJobsUnclaimed() {
        repository.createMissing(List.of(event()));
        assertThat(worker(NOW, false).drainOne()).isEqualTo("paused");
        verifyNoInteractions(validation);
        assertThat(repository.claim(NOW, Duration.ofMinutes(1))).isPresent();
    }
    @Test void orphanedJobCannotOccupyTheWorkerForever() {
        repository.createMissing(List.of(event()));
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
        verify(jobs).abandon(eq(exhausted), anyString(), eq(NOW));
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
        verify(jobs).retry(eq(lease), any(), eq("Temporary validation result storage failure: dev.andreydg.solarsystem.storage.EventCatalogStorageException: offline"), eq(NOW));
        verify(jobs, never()).abandon(any(), anyString(), any());
        assertThat(repository.findById("event").orElseThrow().validationStatus()).isEqualTo(ValidationStatus.PENDING);
    }

    @Test void deterministicProgrammingFailuresAreNotRetriedAsJplOutages() {
        repository.createMissing(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new IllegalArgumentException("invalid geometry input"));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("failed");
        assertThat(worker(NOW.plusSeconds(10000), true).drainOne()).isEqualTo("idle-or-busy");
    }

    @Test void exhaustedJobsCanBeExplicitlyRecoveredAndBackoffSpansALongOutage() {
        repository.createMissing(List.of(event()));
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
        repository.createMissing(List.of(event()));
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

    @Test void publicFailureSummaryNeverContainsRawExceptionText() {
        repository.createMissing(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new JplHorizonsException("<html>private-token-" + "x".repeat(5000)));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("failed");
        var result = repository.findById("event").orElseThrow();
        assertThat(result.jplRawSummary()).hasSizeLessThan(200).doesNotContain("private-token", "<html>");
    }
    @Test void budgetDeferralDoesNotSpendAnAttemptOrHideAnEvent() {
        repository.createMissing(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new ValidationBudget.Exhausted());
        assertThat(worker(NOW, true).drainOne()).isEqualTo("budget-deferred");
        assertThat(repository.findById("event")).contains(event());
        var reclaimed = repository.claim(NOW.plusSeconds(2), Duration.ofMinutes(10)).orElseThrow();
        assertThat(reclaimed.attempt()).isEqualTo(1);
        repository.abandon(reclaimed, "done", NOW.plusSeconds(2));
    }

    @Test void budgetDeferralExhaustionRecordsItsCauseAndRemainsRecoverable() {
        repository.createMissing(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new ValidationBudget.Exhausted());
        Instant time = NOW;
        for (int i = 0; i < 36; i++) {
            assertThat(worker(time, true).drainOne()).isEqualTo("budget-deferred");
            time = time.plusSeconds(2);
        }
        assertThat(worker(time, true).drainOne()).isEqualTo("failed");
        verify(validation, times(36)).validateEvent("event");
        java.util.Optional<?> job = org.springframework.test.util.ReflectionTestUtils.invokeMethod(repository, "inspectJob", "event");
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(job.orElseThrow(), "lastError"))
            .isEqualTo("Retry budget exhausted after budget deferrals");
        assertThat(repository.requeueExhausted(null, 10, time).queued()).isEqualTo(1);
        assertThat(repository.claim(time, Duration.ofMinutes(10)).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test void batchStopsImmediatelyAfterAnInFlightBudgetDeferral() {
        var jobs = mock(ValidationJobs.class);
        var service = mock(EventCatalogService.class);
        var lease = new ValidationJobs.Lease("event", "token", 1);
        when(jobs.claim(any(), any())).thenReturn(java.util.Optional.of(lease), java.util.Optional.empty());
        when(jobs.defer(any(), any(), any())).thenReturn(true);
        when(service.findById("event")).thenReturn(java.util.Optional.of(event()));
        when(validation.validateEvent("event")).thenThrow(new ValidationBudget.Exhausted());
        var worker = new ValidationWorker(jobs, service, validation, new JplProperties("unused", true, 2, 3, 750));
        assertThat(worker.drainBatch()).isEqualTo(new ValidationWorker.DrainResult("budget-deferred", 0));
        verify(jobs, times(1)).claim(any(), any());
    }

    @Test void startupRejectsRetryConfigurationsThatCannotFitOneJob() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(ValidationJobs.class, () -> repository)
            .withBean(EventCatalogService.class, () -> catalog)
            .withBean(JplValidationService.class, () -> validation)
            .withUserConfiguration(ValidationWorker.class);
        var defaults = new JplProperties("unused", true, 2, 3, 750);
        assertThat(ValidationWorker.worstCaseJob(defaults)).isEqualTo(Duration.ofSeconds(319));
        runner.withBean(JplProperties.class, () -> defaults).run(context -> assertThat(context).hasNotFailed());
        for (var properties : List.of(new JplProperties("unused", true, 2, 6, 750),
                new JplProperties("unused", true, 2, 3, 14_167),
                new JplProperties("unused", true, 2, 2, 67_500))) {
            runner.withBean(JplProperties.class, () -> properties)
                .run(context -> assertThat(context).hasFailed().getFailure()
                    .hasRootCauseMessage("JPL retry configuration exceeds the 480s validation budget"));
        }
    }


    @Test void exactStartupBoundariesAndNearLimitClaimWindowAreVisible(org.springframework.boot.test.system.CapturedOutput output) {
        for (var properties : List.of(new JplProperties("unused", true, 2, 3, 14_166),
                new JplProperties("unused", true, 2, 2, 67_499))) {
            assertThatCode(() -> new ValidationWorker(repository, catalog, validation, properties)).doesNotThrowAnyException();
        }
        assertThat(output.getAll()).contains("reservation=479992ms, claim window=8ms", "reservation=479996ms, claim window=4ms");
        assertThat(ValidationWorker.worstCaseJob(new JplProperties("unused", true, 2, 2, 67_500))).isEqualTo(Duration.ofSeconds(480));
    }

    @Test void startupWarnsOnlyWhenTheClaimWindowIsShorterThanATypicalJob(
            org.springframework.boot.test.system.CapturedOutput output) {
        new ValidationWorker(repository, catalog, validation, new JplProperties("unused", true, 2, 3, 750));
        new ValidationWorker(repository, catalog, validation, new JplProperties("unused", true, 2, 2, 66_251));
        new ValidationWorker(repository, catalog, validation, new JplProperties("unused", true, 2, 2, 66_250));
        var lines = output.getAll().lines().filter(line -> line.contains("Validation drain budget=")).toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("INFO", "claim window=161000ms");
        assertThat(lines.get(1)).contains("WARN", "claim window=4996ms");
        assertThat(lines.get(2)).contains("INFO", "claim window=5000ms");
    }

    @Test void catalogReadFailuresRetainPrivateDiagnosticAndAcceptedValues() {
        var accepted = event().validated(NOW, 0.5, null, null, 0.0, "accepted");
        repository.upsert(accepted); repository.enqueue(accepted.id(), NOW);
        var jobs = spy(repository);
        var unavailable = spy(catalog);
        doThrow(new dev.andreydg.solarsystem.storage.EventCatalogStorageException("read offline", new RuntimeException()))
            .when(unavailable).findById("event");
        var worker = new ValidationWorker(jobs, unavailable, validation,
            new JplProperties("unused", true, 2, 3, 1), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(worker.drainOne()).isEqualTo("retry-scheduled");
        verify(jobs).retry(any(), any(), eq("Temporary catalog read failure: dev.andreydg.solarsystem.storage.EventCatalogStorageException: read offline"), eq(NOW));
        assertThat(repository.findById("event")).contains(accepted);
        verifyNoInteractions(validation);
    }

    @Test void drainStopsBeforeClaimingWithoutAFullJobReservation() {
        var jobs = mock(ValidationJobs.class);
        var service = mock(EventCatalogService.class);
        when(jobs.claim(any(), any())).thenReturn(java.util.Optional.of(new ValidationJobs.Lease("event", "token", 1)));
        when(service.findById("event")).thenReturn(java.util.Optional.of(event()));
        when(validation.validateEvent("event")).thenReturn(event());
        when(jobs.complete(any(), any(), any())).thenReturn(true);
        try (var budget = mockStatic(ValidationBudget.class, CALLS_REAL_METHODS)) {
            budget.when(() -> ValidationBudget.hasTimeFor(any(Duration.class))).thenReturn(true, false);
            var worker = new ValidationWorker(jobs, service, validation, new JplProperties("unused", true, 2, 3, 750));
            assertThat(worker.drainBatch()).isEqualTo(new ValidationWorker.DrainResult("budget-reached", 1));
            verify(jobs, times(1)).claim(any(), any());
        }
        try (var ignored = ValidationBudget.start(Duration.ofSeconds(1))) {
            assertThat(ValidationBudget.hasTime()).isTrue();
            assertThat(ValidationBudget.hasTimeFor(Duration.ofSeconds(2))).isFalse();
        }
    }

    @Test void standaloneDrainReservesTheFullWorstCaseJobInsideValidation() {
        var properties = new JplProperties("unused", true, 2, 3, 750);
        repository.createMissing(List.of(event()));
        when(validation.validateEvent("event")).thenAnswer(call -> {
            assertThat(ValidationBudget.isActive()).isTrue();
            assertThat(ValidationBudget.hasTimeFor(ValidationWorker.worstCaseJob(properties))).isTrue();
            return event().validated(NOW, 0.5, null, null, 0.0, "ok");
        });
        var standalone = new ValidationWorker(repository, catalog, validation, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(standalone.drainOne()).isEqualTo("validated");
        verify(validation).validateEvent("event");
        assertThat(ValidationBudget.isActive()).isFalse();
    }

    @Test void standaloneDrainPreservesAnExistingDeadlineAndItsOwnership() {
        repository.createMissing(List.of(event()));
        when(validation.validateEvent("event")).thenAnswer(call -> {
            assertThat(ValidationBudget.isActive()).isTrue();
            assertThat(ValidationBudget.hasTimeFor(Duration.ofMinutes(2))).isFalse();
            return event().validated(NOW, 0.5, null, null, 0.0, "ok");
        });
        try (var budget = ValidationBudget.start(Duration.ofMinutes(1))) {
            assertThat(worker(NOW, true).drainOne()).isEqualTo("validated");
            assertThat(ValidationBudget.isActive()).isTrue();
            assertThat(ValidationBudget.hasTimeFor(Duration.ofMinutes(2))).isFalse();
        }
        assertThat(ValidationBudget.isActive()).isFalse();
    }

    // Recorded from Horizons target 599 on 2026-10-17; no live dependency.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void noCoverageLeavesAVisibleProvisionalRecordWithTheActualReason(boolean before) {
        repository.createMissing(List.of(event())).stored();
        String raw = before ? "No ephemeris for target \"Jupiter\" prior to A.D. 1600-JAN-10 23:58:09.5812 UT"
            : "API VERSION: 1.2\nAPI SOURCE: NASA/JPL Horizons API\n\nNo ephemeris for target \"Jupiter\" after A.D. 2200-JAN-08 23:58:50.8159 UT";
        when(validation.validateEvent("event")).thenThrow(new JplCoverageException(raw));
        assertThat(worker(NOW, true).drainOne()).isEqualTo("unavailable");
        var query = new CatalogQuery(EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS,
            NOW.minusSeconds(100), NOW.plusSeconds(100), false, false, 10, null);
        var visible = catalog.search(query).events().getFirst();
        assertThat(visible.validationStatus()).isEqualTo(ValidationStatus.UNAVAILABLE);
        assertThat(visible.jplRawSummary()).isEqualTo(before
            ? "JPL ephemeris unavailable prior to 1600-JAN-10." : "JPL ephemeris unavailable after 2200-JAN-08.");
        assertThat(repository.createMissing(List.of(event())).stored()).containsExactly(visible);
        assertThat(worker(NOW.plusSeconds(10000), true).drainOne()).isEqualTo("idle-or-busy");
    }

    @Test void rejectedEventCannotBecomeProvisionalAfterAnOutage() {
        var rejected = event().failed("Geometry rejected");
        repository.upsert(rejected);
        repository.enqueue(rejected.id(), NOW);
        when(validation.validateEvent("event")).thenThrow(new JplHorizonsException("outage", true));
        Instant time = NOW;
        for (int i = 1; i <= ValidationWorker.MAX_ATTEMPTS; i++) {
            worker(time, true).drainOne();
            time = time.plus(ValidationWorker.retryDelay(i));
        }
        assertThat(repository.findById("event")).contains(rejected);
        assertThat(rejected.unavailable("new coverage failure")).isSameAs(rejected);
        assertThat(repository.requeueExhausted(null, 100, time).queued()).isEqualTo(1);
        assertThat(repository.claim(time, ValidationWorker.LEASE).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test void pendingEventOutagePublishesSafeSummaryAndRemainsRecoverable() {
        repository.createMissing(List.of(event())).stored();
        when(validation.validateEvent("event")).thenThrow(new JplHorizonsException("private upstream outage", true));
        Instant time = NOW;
        for (int i = 1; i <= ValidationWorker.MAX_ATTEMPTS; i++) {
            worker(time, true).drainOne();
            time = time.plus(ValidationWorker.retryDelay(i));
        }
        var stored = repository.findById("event").orElseThrow();
        assertThat(stored.validationStatus()).isEqualTo(ValidationStatus.UNAVAILABLE);
        assertThat(stored.jplRawSummary()).isEqualTo(
            "Retry budget exhausted: JPL is temporarily unavailable. An administrator can retry this event.");
        assertThat(repository.requeueExhausted(null, 100, time).queued()).isEqualTo(1);
        assertThat(repository.claim(time, ValidationWorker.LEASE).orElseThrow().attempt()).isEqualTo(1);
    }


    @Test void fakeCoverageMonthIsNeverPublished() {
        repository.createMissing(List.of(event()));
        when(validation.validateEvent("event")).thenThrow(new JplCoverageException("No ephemeris prior to A.D. 1599-ABC-01 token=secret"));
        worker(NOW, true).drainOne();
        assertThat(repository.findById("event").orElseThrow().jplRawSummary())
            .isEqualTo("JPL ephemeris is unavailable for this target and date.");
    }

    private CatalogEvent event() {
        return new CatalogEvent("event", EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS,
            NOW, 0.6, null, null, "TEST", null, null, null, null, null, ValidationStatus.PENDING,
            NOW.minusSeconds(100), NOW.plusSeconds(100), NOW, null, null, null);
    }
}

package dev.andreydg.solarsystem.storage;

import static org.assertj.core.api.Assertions.*;
import com.google.cloud.firestore.*;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.EmulatorCredentials;
import dev.andreydg.solarsystem.config.FirestoreProperties;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("firestore")
class FirestoreCatalogTests {
    private Firestore firestore;
    private FirestoreEventCatalogRepository repository;
    private String collection;
    private static final Instant TIME = Instant.parse("2027-01-01T00:00:00Z");

    @BeforeEach void connectOnlyToLocalEmulator() {
        String host = System.getenv("FIRESTORE_EMULATOR_HOST");
        assertThat(host).as("A local Firestore emulator is required").matches("(127\\.0\\.0\\.1|localhost):[0-9]+");
        firestore = FirestoreOptions.newBuilder().setProjectId("solar-system-test").setEmulatorHost(host)
            .setCredentials(new EmulatorCredentials()).build().getService();
        collection = "events-" + UUID.randomUUID();
        repository = new FirestoreEventCatalogRepository(firestore, new FirestoreProperties(collection, "(default)", host));
    }
    @AfterEach void close() throws Exception { if (firestore != null) firestore.close(); }

    @Test void durableJobsSurviveRepositoryRecreationAndFenceExpiredWorkers() throws Exception {
        var event = event("pending", TIME, BodyId.MARS).pending();
        repository.upsertAll(List.of(event));
        var properties = new FirestoreProperties(collection, "(default)", System.getenv("FIRESTORE_EMULATOR_HOST"));
        var firstWorker = new FirestoreValidationJobs(firestore, properties);
        var restartedWorker = new FirestoreValidationJobs(firestore, properties);
        var now = Instant.now().plusSeconds(1);
        var first = firstWorker.claim(now, java.time.Duration.ofSeconds(10)).orElseThrow();
        assertThat(restartedWorker.claim(now, java.time.Duration.ofSeconds(10))).isEmpty();
        var recovered = restartedWorker.claim(now.plusSeconds(10), java.time.Duration.ofSeconds(10)).orElseThrow();
        assertThat(firstWorker.complete(first, event.failed("stale"), now.plusSeconds(11))).isFalse();
        assertThat(restartedWorker.complete(recovered, event.validated(TIME, null, 180.0, null, null, "accepted"), now.plusSeconds(11))).isTrue();
        assertThat(firstWorker.complete(first, event.failed("late stale"), now.plusSeconds(12))).isFalse();
        repository.upsertAll(List.of(event));
        assertThat(repository.findById("pending").orElseThrow().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(restartedWorker.claim(now.plusSeconds(1000), java.time.Duration.ofSeconds(10))).isEmpty();
    }

    @Test void transientBackoffSurvivesRestartAndDoesNotBlockOtherDueJobs() throws Exception {
        repository.upsertAll(List.of(event("pending-a", TIME, BodyId.MARS).pending(), event("pending-b", TIME, BodyId.MARS).pending()));
        var properties = new FirestoreProperties(collection, "(default)", System.getenv("FIRESTORE_EMULATOR_HOST"));
        var jobs = new FirestoreValidationJobs(firestore, properties);
        var now = Instant.now().plusSeconds(1);
        var first = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(jobs.retry(first, now.plusSeconds(60), "outage", now)).isTrue();
        var other = new FirestoreValidationJobs(firestore, properties).claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(other.eventId()).isNotEqualTo(first.eventId());
        jobs.complete(other, repository.findById(other.eventId()).orElseThrow().failed("permanent"), now);
        assertThat(jobs.claim(now.plusSeconds(59), java.time.Duration.ofMinutes(10))).isEmpty();
        assertThat(jobs.claim(now.plusSeconds(60), java.time.Duration.ofMinutes(10)).orElseThrow().attempt()).isEqualTo(2);
    }

    @Test void backfillsOnlyStrandedPendingEventsAndIsIdempotent() throws Exception {
        repository.upsert(event("pending", TIME, BodyId.MARS).pending());
        repository.upsert(event("failed", TIME, BodyId.MARS).failed("permanent"));
        repository.upsert(event("done", TIME, BodyId.MARS));
        var migration = new FirestoreValidationMigration(firestore, collection);
        assertThat(migration.run(false)).isEqualTo(1);
        assertThat(firestore.collection(collection + "-validation").get().get().size()).isZero();
        assertThat(migration.run(true)).isEqualTo(1);
        assertThat(migration.run(true)).isZero();
        assertThat(firestore.collection(collection + "-validation").get().get().size()).isEqualTo(1);
    }

    @Test void requiresExplicitMigrationRatherThanSilentlyOmittingLegacyRecords() throws Exception {
        repository.upsert(event("a", TIME, BodyId.MARS));
        assertThatThrownBy(() -> repository.search(query(false, 10, null))).hasMessageContaining("migration required");
        var legacy = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event("legacy", TIME.plusSeconds(10), BodyId.MARS)));
        for (String field : List.of("displayTime", "displayTimeUtc", "pairKey", "bodies", "readSchemaVersion")) legacy.remove(field);
        firestore.collection(collection).document("legacy").set(legacy).get();
        var migration = new FirestoreIndexMigration(firestore, collection);
        assertThat(migration.run(false).changed()).isEqualTo(1);
        assertThat(firestore.collection(collection).document("legacy").get().get().contains("displayTime")).isFalse();
        assertThat(migration.run(true).ready()).isTrue();
        assertThat(migration.run(true).changed()).isZero();
        assertThat(repository.search(query(false, 10, null)).events()).hasSize(2);
    }

    @Test void pagesEqualTimestampsInBothDirectionsAndFiltersBeforeLimiting() throws Exception {
        for (String id : List.of("a", "b", "c", "d", "e")) repository.upsert(event(id, TIME, BodyId.MARS));
        repository.upsert(event("other", TIME, BodyId.JUPITER));
        repository.upsert(event("outside", TIME.plusSeconds(100), BodyId.MARS));
        new FirestoreIndexMigration(firestore, collection).run(true);
        for (boolean descending : List.of(false, true)) {
            var ids = new ArrayList<String>();
            String cursor = null;
            do {
                var page = repository.search(query(descending, 2, cursor));
                assertThat(page.events()).hasSizeLessThanOrEqualTo(2);
                ids.addAll(page.events().stream().map(CatalogEvent::id).toList());
                cursor = page.nextCursor();
            } while (cursor != null);
            assertThat(ids).containsExactlyElementsOf(descending ? List.of("e", "d", "c", "b", "a") : List.of("a", "b", "c", "d", "e"));
        }
        var onlyMars = new CatalogQuery(null, BodyId.MARS, null, TIME.minusSeconds(1), TIME.plusSeconds(1), true, false, 10, null);
        assertThat(repository.search(onlyMars).events()).hasSize(5);
    }

    @Test void malformedDocumentsDoNotBreakCursorProgressAndBadMigrationCannotActivate() throws Exception {
        repository.upsert(event("a", TIME, BodyId.MARS));
        var bad = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event("b", TIME, BodyId.MARS)));
        bad.put("computedTimeUtc", "not-a-date");
        firestore.collection(collection).document("b").set(bad).get();
        repository.upsert(event("c", TIME, BodyId.MARS));
        assertThat(new FirestoreIndexMigration(firestore, collection).run(true).ready()).isFalse();
        // Simulate corruption after an earlier successful activation.
        firestore.collection("_catalogMetadata").document(collection).set(Map.of("indexedQueriesReady", true)).get();
        var first = repository.search(query(false, 2, null));
        assertThat(first.events()).extracting(CatalogEvent::id).containsExactly("a");
        assertThat(first.nextCursor()).isNotNull();
        assertThat(repository.search(query(false, 2, first.nextCursor())).events()).extracting(CatalogEvent::id).containsExactly("c");
    }

    @Test void generationCannotReplaceValidatedLegacyDocumentOmittedByIndex() throws Exception {
        var accepted = event("legacy-accepted", TIME, BodyId.MARS);
        var legacy = new HashMap<>(FirestoreEventCatalogRepository.toDocument(accepted));
        for (String field : List.of("displayTime", "pairKey", "bodies", "readSchemaVersion")) legacy.remove(field);
        firestore.collection(collection).document(accepted.id()).set(legacy).get();
        var predicted = new CatalogEvent(accepted.id(), accepted.type(), accepted.bodyA(), accepted.bodyB(), TIME,
            null, 179.0, null, "VSOP", null, null, null, null, null, ValidationStatus.PENDING,
            TIME.minusSeconds(100), TIME.plusSeconds(200), TIME, null, null, null);
        assertThat(repository.upsertAll(List.of(predicted))).containsExactly(accepted);
        assertThat(repository.findById(accepted.id())).contains(accepted);
    }

    @Test void rejectsReservedFirestoreCursorIdsBeforeAccessingStorage() {
        for (String id : List.of(".", "..", "__reserved__")) {
            assertThatThrownBy(() -> query(false, 1, CatalogQuery.encode(TIME, id)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid pagination cursor");
        }
    }

    @Test void corruptExistingRecordsAreQuarantinedAndRepairedWithoutRollingBackSiblings() throws Exception {
        var repaired = event("broken", TIME, BodyId.MARS);
        var corrupt = new HashMap<>(FirestoreEventCatalogRepository.toDocument(repaired));
        corrupt.put("computedTimeUtc", "not-a-date");
        var ref = firestore.collection(collection).document("broken");
        ref.set(corrupt).get();
        var sibling = event("sibling", TIME, BodyId.JUPITER);
        assertThat(repository.upsertAll(List.of(repaired, sibling))).containsExactly(repaired, sibling);
        assertThat(repository.findById("broken")).contains(repaired);
        assertThat(repository.findById("sibling")).contains(sibling);
        var quarantine = ref.collection("_corrupt").get().get().getDocuments();
        assertThat(quarantine).hasSize(1);
        assertThat(quarantine.getFirst().getData()).isEqualTo(corrupt);
        assertThat(repository.upsertAll(List.of(repaired, sibling))).containsExactly(repaired, sibling);
        assertThat(ref.collection("_corrupt").get().get().size()).isEqualTo(1);
    }

    @Test void requeuePreservesAcceptedDataAndPoisonedRecordsReleaseTheLease() throws Exception {
        var accepted = event("accepted", TIME, BodyId.MARS);
        repository.upsert(accepted);
        var jobs = new FirestoreValidationJobs(firestore, new FirestoreProperties(collection, "(default)", System.getenv("FIRESTORE_EMULATOR_HOST")));
        var now = Instant.now();
        jobs.enqueue("accepted", now);
        assertThat(repository.findById("accepted")).contains(accepted);
        var lease = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        jobs.abandon(lease, "new validation failed", now);
        assertThat(repository.findById("accepted")).contains(accepted);

        var bad = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event("poison", TIME, BodyId.MARS).pending()));
        bad.put("computedTimeUtc", "invalid-date");
        firestore.collection(collection).document("poison").set(bad).get();
        var initial = new HashMap<>(FirestoreValidationJobs.initial(now));
        initial.put("attempts", 6);
        firestore.collection(collection + "-validation").document("poison").set(initial).get();
        var catalog = new EventCatalogService(org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider.class), repository);
        var validation = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplValidationService.class);
        var worker = new dev.andreydg.solarsystem.jpl.ValidationWorker(jobs, catalog, validation,
            new dev.andreydg.solarsystem.config.JplProperties("unused", true, 2, 3, 1));
        assertThat(worker.drainOne()).isEqualTo("failed");
        assertThat(worker.drainOne()).isEqualTo("idle-or-busy");
        assertThat(firestore.collection(collection + "-validation").document("poison").get().get().getString("state")).isEqualTo("failed");
        org.mockito.Mockito.verifyNoInteractions(validation);
    }

    @Test void exhaustedRecoveryIsBoundedAndDoesNotRequeueScientificRejections() throws Exception {
        var jobs = new FirestoreValidationJobs(firestore, new FirestoreProperties(collection, "(default)", System.getenv("FIRESTORE_EMULATOR_HOST")));
        var now = Instant.now().plusSeconds(1);
        for (String id : List.of("exhausted-a", "exhausted-b", "rejected")) {
            repository.upsertAll(List.of(event(id, TIME, BodyId.MARS).pending()));
            var lease = jobs.claim(now, java.time.Duration.ofMinutes(1)).orElseThrow();
            jobs.abandon(lease, id.startsWith("exhausted") ? "Retry budget exhausted" : "bad geometry", now);
        }
        var first = jobs.requeueExhausted(null, 1, now);
        assertThat(first.queued()).isEqualTo(1);
        assertThat(first.nextCursor()).isEqualTo("exhausted-a");
        var last = jobs.requeueExhausted(first.nextCursor(), 1, now);
        assertThat(last.queued()).isEqualTo(1);
        assertThat(last.nextCursor()).isNull();
        assertThat(firestore.collection(collection + "-validation").document("rejected").get().get().getString("state")).isEqualTo("failed");
    }

    @Test void poisonJobsOrDeletedEventsCannotWedgeRecoveryAndDeferralRefundsTheAttempt() throws Exception {
        var jobs = new FirestoreValidationJobs(firestore, new FirestoreProperties(collection, "(default)", System.getenv("FIRESTORE_EMULATOR_HOST")));
        var now = Instant.now();
        firestore.collection(collection + "-validation").document("a-poison").set(Map.of("state", "ready", "availableAt", FirestoreEventCatalogRepository.timestamp(now.minusSeconds(1)))).get();
        repository.upsert(event("good", TIME, BodyId.MARS));
        jobs.enqueue("good", now);
        var lease = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(lease.eventId()).isEqualTo("good");
        assertThat(firestore.collection(collection + "-validation").document("a-poison").get().get().contains("availableAt")).isFalse();
        assertThat(jobs.defer(lease, now.plusSeconds(1), now)).isTrue();
        assertThat(jobs.claim(now, java.time.Duration.ofMinutes(10))).isEmpty();
        lease = jobs.claim(now.plusSeconds(1), java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(lease.attempt()).isEqualTo(1);
        jobs.abandon(lease, "Retry budget exhausted", now.plusSeconds(1));
        firestore.collection(collection + "-validation").document("a-deleted").set(Map.of("retryExhausted", true, "state", "failed")).get();
        var recovered = jobs.requeueExhausted(null, 100, now.plusSeconds(2));
        assertThat(recovered.queued()).isEqualTo(1);
        assertThat(jobs.requeueExhausted(null, 100, now.plusSeconds(2)).queued()).isZero();
        assertThat(firestore.collection(collection + "-validation").document("a-deleted").get().get().getBoolean("retryExhausted")).isFalse();
    }
    @Test void privateFailureTextLivesOnlyOnTheJob() throws Exception {
        var jobs = new FirestoreValidationJobs(firestore, new FirestoreProperties(collection, "(default)", System.getenv("FIRESTORE_EMULATOR_HOST")));
        repository.upsert(event("private", TIME, BodyId.MARS));
        var now = Instant.now(); jobs.enqueue("private", now);
        var lease = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        String raw = "<html>private:" + "secret".repeat(800);
        jobs.complete(lease, event("private", TIME, BodyId.MARS).failed("Safe public reason"), now, raw);
        assertThat(repository.findById("private").orElseThrow().jplRawSummary()).doesNotContain("secret", "<html>");
        assertThat(firestore.collection(collection + "-validation").document("private").get().get().getString("lastError")).isEqualTo(raw);
    }

    private CatalogQuery query(boolean descending, int limit, String cursor) {
        return new CatalogQuery(EventType.OPPOSITION, BodyId.MARS, BodyId.EARTH, TIME.minusSeconds(1), TIME.plusSeconds(20),
            true, descending, limit, cursor);
    }
    private CatalogEvent event(String id, Instant time, BodyId target) {
        return new CatalogEvent(id, EventType.OPPOSITION, BodyId.EARTH, target, time, null, 180.0, null, "TEST",
            time, null, 180.0, null, "JPL_HORIZONS", ValidationStatus.VALIDATED,
            TIME.minusSeconds(100), TIME.plusSeconds(200), TIME, TIME, null, "test");
    }
}

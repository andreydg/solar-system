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
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
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

    @Test void obsoleteOppositionRecordsRemainArchivedButCannotLeakIntoCurrentSearches() throws Exception {
        var old = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event("old-polluted-opposition", TIME, BodyId.MARS)));
        old.remove("algorithmVersion");
        old.put("computedAngleDeg", 0.0);
        firestore.collection(collection).document("old-polluted-opposition").set(old).get();
        repository.upsert(event("current", TIME, BodyId.MARS));
        new FirestoreIndexMigration(firestore, collection).run(true);
        assertThat(repository.search(query(false, 10, null)).events()).extracting(CatalogEvent::id).containsExactly("current");
        assertThat(repository.findById("old-polluted-opposition")).isPresent();
        assertThat(new FirestoreValidationMigration(firestore, collection).run(true)).isZero();
    }

    @Test void regenerationDoesNotReturnARejectedEventAsFreshProvisionalData() throws Exception {
        var rejected = event("rejected", TIME, BodyId.MARS).failed("Geometry rejected");
        repository.upsert(rejected);
        assertThat(repository.createMissing(List.of(rejected.pending())).stored().getFirst().validationStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(firestore.collection(collection + "-validation").get().get().size()).isZero();
    }

    @Test void durableJobsSurviveRepositoryRecreationAndFenceExpiredWorkers() throws Exception {
        var event = event("pending", TIME, BodyId.MARS).pending();
        repository.createMissing(List.of(event));
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
        repository.createMissing(List.of(event));
        assertThat(repository.findById("pending").orElseThrow().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(restartedWorker.claim(now.plusSeconds(1000), java.time.Duration.ofSeconds(10))).isEmpty();
    }

    @Test void transientBackoffSurvivesRestartAndDoesNotBlockOtherDueJobs() throws Exception {
        repository.createMissing(List.of(event("pending-a", TIME, BodyId.MARS).pending(), event("pending-b", TIME, BodyId.MARS).pending()));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 10})
    void malformedScanLogsCompleteForLookaheadAndExhaustedResults(int limit,
        org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var broken = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event("0-broken", TIME, BodyId.MARS)));
        broken.put("computedTimeUtc", "not-a-date");
        firestore.collection(collection).document("0-broken").set(broken).get();
        repository.upsert(event("a", TIME, BodyId.MARS));
        repository.upsert(event("b", TIME, BodyId.MARS));
        activateIndex();
        var page = repository.search(query(false, limit, null));
        assertThat(page.events()).hasSize(limit == 1 ? 1 : 2);
        if (limit == 1) assertThat(page.nextCursor()).isNotNull();
        else assertThat(page.nextCursor()).isNull();
        assertThat(scanWarnings(output)).singleElement().asString()
            .contains("1 malformed", "outcome complete").doesNotContain("outcome aborted");
    }

    @Test void malformedDocumentsDoNotBreakCursorProgressAndBadMigrationCannotActivate() throws Exception {
        repository.upsert(event("a", TIME, BodyId.MARS));
        var bad = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event("b", TIME, BodyId.MARS)));
        bad.put("computedTimeUtc", "not-a-date");
        firestore.collection(collection).document("b").set(bad).get();
        repository.upsert(event("c", TIME, BodyId.MARS));
        assertThat(new FirestoreIndexMigration(firestore, collection).run(true).ready()).isFalse();
        // Simulate corruption after an earlier successful activation.
        firestore.collection("_catalogMetadata").document(collection).set(Map.of("indexedQueriesReady", true, "readSchemaVersion", 2)).get();
        var first = repository.search(query(false, 2, null));
        assertThat(first.events()).extracting(CatalogEvent::id).containsExactly("a", "c");
        assertThat(first.nextCursor()).isNull();
    }

    @Test void generationCannotReplaceValidatedLegacyDocumentOmittedByIndex() throws Exception {
        var accepted = event("legacy-accepted", TIME, BodyId.MARS);
        var legacy = new HashMap<>(FirestoreEventCatalogRepository.toDocument(accepted));
        for (String field : List.of("displayTime", "pairKey", "bodies", "readSchemaVersion")) legacy.remove(field);
        firestore.collection(collection).document(accepted.id()).set(legacy).get();
        var predicted = new CatalogEvent(accepted.id(), accepted.type(), accepted.bodyA(), accepted.bodyB(), TIME,
            null, 179.0, null, "VSOP", null, null, null, null, null, ValidationStatus.PENDING,
            TIME.minusSeconds(100), TIME.plusSeconds(200), TIME, null, null, null);
        assertThat(repository.createMissing(List.of(predicted)).stored()).containsExactly(accepted);
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
        corrupt.put("validationStatus", "pending");
        corrupt.keySet().removeIf(key -> key.startsWith("validated"));
        var ref = firestore.collection(collection).document("broken");
        ref.set(corrupt).get();
        var sibling = event("sibling", TIME, BodyId.JUPITER);
        assertThat(repository.createMissing(List.of(repaired, sibling)).stored()).containsExactly(repaired, sibling);
        assertThat(repository.findById("broken")).contains(repaired);
        assertThat(repository.findById("sibling")).contains(sibling);
        var quarantine = ref.collection("_corrupt").get().get().getDocuments();
        assertThat(quarantine).hasSize(1);
        assertThat(quarantine.getFirst().getData()).isEqualTo(corrupt);
        assertThat(repository.createMissing(List.of(repaired, sibling)).stored()).containsExactly(repaired, sibling);
        assertThat(ref.collection("_corrupt").get().get().size()).isEqualTo(1);
    }

    @Test void acceptedLegacyRepresentationsAndCorruptionNeverDowngrade() throws Exception {
        for (String shape : List.of("integer", "timestamp", "numeric-string", "unknown-status", "missing-field", "status-only")) {
            var accepted = event(shape, TIME, BodyId.MARS);
            var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(accepted));
            switch (shape) {
                case "integer" -> raw.put("jplDeltaKm", 12L);
                case "timestamp" -> raw.put("computedTimeUtc", FirestoreEventCatalogRepository.timestamp(TIME));
                case "numeric-string" -> raw.put("jplDeltaKm", "2.5");
                case "unknown-status" -> raw.put("validationStatus", "damaged-status");
                case "missing-field" -> raw.remove("rangeEndUtc");
                case "status-only" -> {
                    raw.put("validationStatus", "VALIDATED");
                    raw.keySet().removeIf(key -> key.startsWith("validated"));
                    raw.put("computedTimeUtc", "not-a-date");
                }
            }
            var ref = firestore.collection(collection).document(shape);
            ref.set(raw).get();
            var before = ref.get().get();
            boolean parseable = List.of("integer", "timestamp", "numeric-string").contains(shape);
            for (int repeat = 0; repeat < 3; repeat++) {
                var receipt = repository.createMissing(List.of(accepted));
                assertThat(ref.get().get().getData()).as(shape).isEqualTo(before.getData());
                assertThat(receipt.created()).isEmpty();
                assertThat(receipt.stored()).hasSize(parseable ? 1 : 0);
                if (parseable) {
                    assertThat(receipt.stored().getFirst().validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
                    if (shape.equals("integer")) assertThat(receipt.stored().getFirst().jplDeltaKm()).isEqualTo(12.0);
                    if (shape.equals("numeric-string")) assertThat(receipt.stored().getFirst().jplDeltaKm()).isEqualTo(2.5);
                }
                var copies = ref.collection("_corrupt").get().get().getDocuments();
                assertThat(copies).hasSize(parseable ? 0 : 1);
                if (!parseable) {
                    assertThat(copies.getFirst().getData()).isEqualTo(before.getData());
                    assertThat(copies.getFirst().getId()).isEqualTo(
                        before.getUpdateTime().getSeconds() + "-" + before.getUpdateTime().getNanos());
                }
            }
        }
    }

    @Test void repairLogsTheDiagnosticAndReceiptsOnlyNewOrRepairedRecords(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var first = event("first", TIME, BodyId.MARS);
        var sibling = event("second", TIME, BodyId.MARS);
        assertThat(repository.createMissing(List.of(first)).created()).containsExactly(first);
        assertThat(repository.createMissing(List.of(first, sibling)).created()).containsExactly(sibling);
        var broken = new HashMap<>(FirestoreEventCatalogRepository.toDocument(first));
        broken.put("validationStatus", "pending");
        broken.keySet().removeIf(key -> key.startsWith("validated"));
        broken.put("computedTimeUtc", "not-a-date");
        firestore.collection(collection).document(first.id()).set(broken).get();
        assertThat(repository.createMissing(List.of(first, sibling)).created()).containsExactly(first);
        assertThat(repository.createMissing(List.of(first, sibling)).created()).isEmpty();
        assertThat(output.getAll()).contains("DateTimeParseException: Text 'not-a-date'");
    }

    @Test void fillsBothDirectionsPastMoreThanTwentyMalformedRows() throws Exception {
        var batch = firestore.batch();
        for (String prefix : List.of("a-", "z-")) for (int i = 0; i < 25; i++) {
            var id = prefix + "%02d".formatted(i);
            var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event(id, TIME, BodyId.MARS)));
            raw.put("computedTimeUtc", "invalid");
            batch.set(firestore.collection(collection).document(id), raw);
        }
        batch.commit().get();
        repository.upsert(event("m-1", TIME, BodyId.MARS));
        repository.upsert(event("m-2", TIME, BodyId.MARS));
        firestore.collection("_catalogMetadata").document(collection).set(Map.of("indexedQueriesReady", true, "readSchemaVersion", 2)).get();
        for (boolean descending : List.of(false, true)) {
            var page = repository.search(query(descending, 1, null));
            assertThat(page.events()).extracting(CatalogEvent::id).containsExactly(descending ? "m-2" : "m-1");
            assertThat(page.nextCursor()).isNotNull();
            var next = repository.search(query(descending, 1, page.nextCursor()));
            assertThat(next.events()).extracting(CatalogEvent::id).containsExactly(descending ? "m-1" : "m-2");
            assertThat(next.nextCursor()).isNull();
        }
    }

    @Test void widespreadCorruptionHasABoundedActionableFailure() throws Exception {
        for (int start = 0; start < 2001; start += 450) {
            var batch = firestore.batch();
            for (int i = start; i < Math.min(start + 450, 2001); i++) {
                String id = "broken-" + i;
                var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event(id, TIME, BodyId.MARS)));
                raw.put("computedTimeUtc", "invalid");
                batch.set(firestore.collection(collection).document(id), raw);
            }
            batch.commit().get();
        }
        firestore.collection("_catalogMetadata").document(collection).set(Map.of("indexedQueriesReady", true, "readSchemaVersion", 2)).get();
        assertThatThrownBy(() -> repository.search(query(false, 1, null)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("503").hasMessageContaining("operator repair required");
    }

    @Test void transactionWritesReserveRoomForQuarantineEventAndWorkerJob() throws Exception {
        var candidates = new ArrayList<CatalogEvent>();
        var batch = firestore.batch();
        for (int i = 0; i < 250; i++) {
            var event = pending("repair-" + i);
            candidates.add(event);
            var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event));
            raw.put("computedTimeUtc", "bad");
            raw.put("validationStatus", "pending");
            raw.keySet().removeIf(key -> key.startsWith("validated"));
            batch.set(firestore.collection(collection).document(event.id()), raw);
        }
        batch.commit().get();
        var instrumented = org.mockito.Mockito.mock(Firestore.class, org.mockito.AdditionalAnswers.delegatesTo(firestore));
        var transactions = new ArrayList<Integer>();
        org.mockito.Mockito.doAnswer(call -> {
            Transaction.Function<Object> action = call.getArgument(0);
            return firestore.runTransaction(transaction -> {
                var counted = org.mockito.Mockito.mock(Transaction.class, org.mockito.AdditionalAnswers.delegatesTo(transaction));
                var result = action.updateCallback(counted);
                var invocations = org.mockito.Mockito.mockingDetails(counted).getInvocations();
                long writes = invocations.stream().filter(i -> Set.of("set", "create", "update", "delete").contains(i.getMethod().getName())).count();
                int documents = invocations.stream().filter(i -> i.getMethod().getName().equals("getAll"))
                    .mapToInt(i -> (int) Arrays.stream((DocumentReference[]) i.getRawArguments()[0])
                        .filter(ref -> ref.getParent().getId().equals(collection)).count()).sum();
                // Count the actual event, quarantine, and job writes made on this layer.
                assertThat(writes).isEqualTo(3L * documents);
                assertThat(writes).as("actual transaction writes including jobs").isLessThanOrEqualTo(500);
                transactions.add(documents);
                return result;
            });
        }).when(instrumented).runTransaction(org.mockito.ArgumentMatchers.<Transaction.Function<Object>>any());
        var checked = new FirestoreEventCatalogRepository(instrumented, new FirestoreProperties(collection, "(default)", ""));
        assertThat(checked.createMissing(candidates).created()).hasSize(250);
        assertThat(transactions.stream().mapToInt(Integer::intValue).sum()).isEqualTo(250);
    }

    @Test void scanCeilingReturnsValidResultsAndResumesAfterTheLastScannedDocument(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        seedMalformed(2050);
        repository.upsert(event("a-valid", TIME, BodyId.MARS));
        repository.upsert(event("z-valid", TIME, BodyId.MARS));
        for (boolean descending : List.of(false, true)) {
            int before = scanWarnings(output).size();
            var page = repository.search(query(descending, 50, null));
            assertThat(page.events()).extracting(CatalogEvent::id).containsExactly(descending ? "z-valid" : "a-valid");
            var position = CatalogQuery.decode(page.nextCursor());
            assertThat(position.id()).startsWith("broken-");
            assertThat(position.time()).isEqualTo(TIME);
            assertThat(scanWarnings(output)).hasSize(before + 1);
            assertThat(scanWarnings(output).getLast()).contains("1999 malformed", "partial page at scan limit").hasSizeLessThan(400);
            var next = repository.search(query(descending, 50, page.nextCursor()));
            assertThat(next.events()).extracting(CatalogEvent::id).containsExactly(descending ? "a-valid" : "z-valid");
            assertThat(next.nextCursor()).isNull();
        }
    }

    @Test void allInvalidScanLogsOneBoundedWarningIncludingThe503(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        seedMalformed(2050);
        assertThatThrownBy(() -> repository.search(query(false, 1, null))).hasMessageContaining("503");
        assertThat(scanWarnings(output)).hasSize(1);
        assertThat(scanWarnings(output).getFirst()).contains("2000 malformed", "503 operator repair required", "broken-0000", "broken-0004")
            .doesNotContain("broken-0005").hasSizeLessThan(400);
    }

    @Test void staleDisplayTimesStillPageInActualIndexOrderInBothDirections() throws Exception {
        for (int i = 0; i < 3; i++) {
            String id = List.of("a", "b", "c").get(i);
            var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event(id, TIME.plusSeconds(i), BodyId.MARS)));
            raw.put("validatedTimeUtc", TIME.plusSeconds(2 - i).toString());
            firestore.collection(collection).document(id).set(raw).get();
        }
        activateIndex();
        for (boolean descending : List.of(false, true)) {
            var ids = new ArrayList<String>();
            String cursor = null;
            do {
                var page = repository.search(query(descending, 1, cursor));
                ids.addAll(page.events().stream().map(CatalogEvent::id).toList());
                cursor = page.nextCursor();
                assertThat(ids).hasSizeLessThanOrEqualTo(3);
            } while (cursor != null);
            assertThat(ids).containsExactlyElementsOf(descending ? List.of("c", "b", "a") : List.of("a", "b", "c"));
        }
    }

    @Test void acceptedCorruptVersionsAreWrittenAndLoggedOnceEvenAcrossRepeatedGeneration(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var candidate = event("accepted-broken", TIME, BodyId.MARS);
        var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(candidate));
        raw.put("computedTimeUtc", "not-a-date");
        var ref = firestore.collection(collection).document(candidate.id());
        for (int version = 0; version < 2; version++) {
            raw.put("operatorNote", "version-" + version);
            ref.set(raw).get();
            var original = ref.get().get();
            var copyRef = ref.collection("_corrupt").document(original.getUpdateTime().getSeconds() + "-" + original.getUpdateTime().getNanos());
            repository.createMissing(List.of(candidate));
            var firstCopy = copyRef.get().get();
            for (int repeat = 0; repeat < 3; repeat++) {
                assertThat(repository.createMissing(List.of(candidate)).stored()).isEmpty();
                assertThat(copyRef.get().get().getUpdateTime()).isEqualTo(firstCopy.getUpdateTime());
                assertThat(copyRef.get().get().getData()).isEqualTo(original.getData());
                assertThat(ref.get().get().getUpdateTime()).isEqualTo(original.getUpdateTime());
            }
            assertThat(ref.collection("_corrupt").get().get().size()).isEqualTo(version + 1);
        }
        assertThat(output.getAll().lines().filter(line -> line.contains("Quarantining malformed catalog record accepted-broken"))).hasSize(2);
    }

    @Test void concurrentFirestoreCreationReceiptsAreDisjointCompleteAndRepairOnlyOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            String prefix = "round-" + round + "-";
            var common = new ArrayList<CatalogEvent>();
            for (int i = 0; i < 20; i++) common.add(event(prefix + "common-" + i, TIME, BodyId.MARS));
            var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(common.getFirst()));
            raw.put("computedTimeUtc", "invalid"); raw.put("validationStatus", "pending");
            raw.keySet().removeIf(key -> key.startsWith("validated"));
            firestore.collection(collection).document(common.getFirst().id()).set(raw).get();
            var start = new java.util.concurrent.CyclicBarrier(4);
            var expected = new HashSet<String>();
            common.forEach(event -> expected.add(event.id()));
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
                var tasks = new ArrayList<java.util.concurrent.Callable<EventCatalogRepository.WriteResult>>();
                for (int worker = 0; worker < 4; worker++) {
                    var candidates = new ArrayList<>(common);
                    for (int i = 0; i < 10; i++) {
                        var event = event(prefix + "worker-" + worker + "-" + i, TIME, BodyId.MARS);
                        candidates.add(event); expected.add(event.id());
                    }
                    tasks.add(() -> { start.await(5, java.util.concurrent.TimeUnit.SECONDS); return repository.createMissing(candidates); });
                }
                var created = new ArrayList<String>();
                for (var future : executor.invokeAll(tasks)) {
                    var receipt = future.get();
                    assertThat(receipt.stored()).hasSize(30);
                    created.addAll(receipt.created().stream().map(CatalogEvent::id).toList());
                }
                assertThat(created).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
            }
            assertThat(firestore.collection(collection).document(common.getFirst().id()).collection("_corrupt").get().get().size()).isEqualTo(1);
        }
    }

    @Test void concurrentGeneratorsCreateEachFirestoreJobOnce() throws Exception {
        var provider = new dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider(
            new dev.andreydg.solarsystem.ephemeris.KeplerianEphemerisProvider(), null, null);
        var service = new EventCatalogService(provider, repository);
        var barrier = new java.util.concurrent.CyclicBarrier(4);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<List<CatalogEvent>>>();
            for (int i = 0; i < 4; i++) tasks.add(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return service.generate(EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS,
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2051-01-01T00:00:00Z"));
            });
            Set<String> ids = null;
            for (var future : executor.invokeAll(tasks)) {
                var actual = future.get().stream().map(CatalogEvent::id).collect(java.util.stream.Collectors.toSet());
                if (ids == null) ids = actual; else assertThat(actual).isEqualTo(ids);
            }
            var generatedJobs = firestore.collection(collection + "-validation").get().get().getDocuments();
            assertThat(generatedJobs).hasSizeGreaterThan(5);
            assertThat(generatedJobs.stream().map(DocumentSnapshot::getId).toList()).containsExactlyInAnyOrderElementsOf(ids);
            var lease = jobs().claim(Instant.now().plusSeconds(1), java.time.Duration.ofMinutes(10)).orElseThrow();
            var before = firestore.collection(collection + "-validation").document(lease.eventId()).get().get().getData();
            service.generate(EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2051-01-01T00:00:00Z"));
            assertThat(firestore.collection(collection + "-validation").document(lease.eventId()).get().get().getData()).isEqualTo(before);
        }
    }

    @Test void concurrentAcceptedCorruptionCreatesOneImmutableCopyAndOneCommittedWarning(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var candidate = event("concurrent-accepted-corrupt", TIME, BodyId.MARS);
        var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(candidate));
        raw.put("computedTimeUtc", "invalid");
        var ref = firestore.collection(collection).document(candidate.id());
        ref.set(raw).get();
        var original = ref.get().get();
        var firstAttempts = new java.util.concurrent.CyclicBarrier(4);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var instrumented = org.mockito.Mockito.mock(Firestore.class, org.mockito.AdditionalAnswers.delegatesTo(firestore));
        org.mockito.Mockito.doAnswer(call -> {
            Transaction.Function<Object> action = call.getArgument(0);
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            return firestore.runTransaction(transaction -> {
                attempts.incrementAndGet();
                var result = action.updateCallback(transaction);
                // All four first attempts read the same absent copy before any can commit.
                if (first.getAndSet(false)) firstAttempts.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return result;
            });
        }).when(instrumented).runTransaction(org.mockito.ArgumentMatchers.<Transaction.Function<Object>>any());
        var concurrent = new FirestoreEventCatalogRepository(instrumented, new FirestoreProperties(collection, "(default)", ""));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4)
                .<java.util.concurrent.Callable<EventCatalogRepository.WriteResult>>mapToObj(i -> () -> concurrent.createMissing(List.of(candidate))).toList();
            for (var result : executor.invokeAll(tasks)) {
                assertThat(result.get().stored()).isEmpty();
                assertThat(result.get().created()).isEmpty();
            }
        }
        assertThat(attempts.get()).as("real contention retried at least one transaction").isGreaterThan(4);
        var copies = ref.collection("_corrupt").get().get().getDocuments();
        assertThat(copies).hasSize(1);
        assertThat(copies.getFirst().getData()).isEqualTo(raw);
        assertThat(ref.get().get().getUpdateTime()).isEqualTo(original.getUpdateTime());
        assertThat(output.getAll().lines().filter(line -> line.contains("Quarantining malformed catalog record " + candidate.id())))
            .hasSize(1);
    }

    @Test void scanCeilingCursorResumesPastMalformedNonTokenDocumentIdsInBothDirections() throws Exception {
        String unusualId = "unsafe # | " + "x".repeat(200);
        for (boolean descending : List.of(false, true)) {
            var firstTime = TIME.plusSeconds(descending ? 3 : 0);
            var brokenTime = TIME.plusSeconds(descending ? 2 : 1);
            var cursorTime = TIME.plusSeconds(descending ? 1 : 2);
            var lastTime = TIME.plusSeconds(descending ? 0 : 3);
            for (int start = 0; start < 1998; start += 450) {
                var batch = firestore.batch();
                for (int i = start; i < Math.min(start + 450, 1998); i++) {
                    var broken = event("bad-" + i, brokenTime, BodyId.MARS);
                    var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(broken));
                    raw.put("computedTimeUtc", "invalid");
                    batch.set(firestore.collection(collection).document(broken.id()), raw);
                }
                batch.commit().get();
            }
            var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event(unusualId, cursorTime, BodyId.MARS)));
            raw.put("computedTimeUtc", "invalid");
            firestore.collection(collection).document(unusualId).set(raw).get();
            repository.upsert(event("first-valid", firstTime, BodyId.MARS));
            repository.upsert(event("last-valid", lastTime, BodyId.MARS));
            activateIndex();
            var first = repository.search(query(descending, 10, null));
            assertThat(first.events()).extracting(CatalogEvent::id).containsExactly("first-valid");
            assertThat(CatalogQuery.decode(first.nextCursor())).isEqualTo(new CatalogQuery.Position(cursorTime, unusualId));
            var next = repository.search(query(descending, 10, first.nextCursor()));
            assertThat(next.events()).extracting(CatalogEvent::id).containsExactly("last-valid");
            assertThat(next.nextCursor()).isNull();
        }
    }

    @Test void interruptedStorageScanLogsAbortedRatherThanComplete(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        seedMalformed(2);
        var documents = firestore.collection(collection).get().get();
        var query = org.mockito.Mockito.mock(CollectionReference.class, org.mockito.Mockito.RETURNS_SELF);
        org.mockito.Mockito.when(query.get()).thenReturn(com.google.api.core.ApiFutures.immediateFuture(documents),
            com.google.api.core.ApiFutures.immediateFailedFuture(new java.io.IOException("storage transport failed")));
        var instrumented = org.mockito.Mockito.mock(Firestore.class, org.mockito.AdditionalAnswers.delegatesTo(firestore));
        org.mockito.Mockito.doReturn(query).when(instrumented).collection(collection);
        var failing = new FirestoreEventCatalogRepository(instrumented, new FirestoreProperties(collection, "(default)", ""));
        assertThatThrownBy(() -> failing.search(query(false, 1, null))).isInstanceOf(EventCatalogStorageException.class);
        var warnings = scanWarnings(output);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("outcome aborted").doesNotContain("outcome complete");
    }

    private List<String> scanWarnings(org.springframework.boot.test.system.CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("WARN") && line.contains("FirestoreEventCatalogRepository")).toList();
    }
    private void activateIndex() throws Exception {
        firestore.collection("_catalogMetadata").document(collection).set(Map.of("indexedQueriesReady", true, "readSchemaVersion", 2L)).get();
    }
    private void seedMalformed(int count) throws Exception {
        for (int start = 0; start < count; start += 450) {
            var batch = firestore.batch();
            for (int i = start; i < Math.min(start + 450, count); i++) {
                String id = "broken-%04d".formatted(i);
                var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event(id, TIME, BodyId.MARS)));
                raw.put("computedTimeUtc", "invalid");
                batch.set(firestore.collection(collection).document(id), raw);
            }
            batch.commit().get();
        }
        activateIndex();
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
            repository.createMissing(List.of(event(id, TIME, BodyId.MARS).pending()));
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

    @Test void failingWorkerKeepsAcceptedBytesAndPersistsPrivateDiagnostics() throws Exception {
        var jobs = jobs();
        for (String mode : List.of("accepted", "permanent", "transient")) {
            var event = mode.equals("accepted") ? event(mode, TIME, BodyId.MARS) : pending(mode);
            repository.upsert(event);
            var ref = firestore.collection(collection).document(mode);
            var before = ref.get().get().getData();
            jobs.enqueue(mode, Instant.now().minusSeconds(1));
            var validation = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplValidationService.class);
            var failure = new dev.andreydg.solarsystem.jpl.JplHorizonsException("private-token-" + "x".repeat(9000), mode.equals("transient"));
            org.mockito.Mockito.when(validation.validateEvent(mode)).thenThrow(failure);
            var worker = worker(jobs, validation);
            assertThat(worker.drainOne()).isEqualTo(mode.equals("transient") ? "retry-scheduled" : "failed");
            var job = firestore.collection(collection + "-validation").document(mode).get().get();
            assertThat(job.getString("lastError")).hasSize(8000).isEqualTo(failure.toString().substring(0, 8000));
            if (mode.equals("accepted")) assertThat(ref.get().get().getData()).isEqualTo(before);
            assertThat(String.valueOf(ref.get().get().get("jplRawSummary"))).doesNotContain("private-token");
        }
    }

    @Test void privateDiagnosticsAreCappedAtEveryFirestoreStoreBoundary() throws Exception {
        var jobs = jobs();
        var now = Instant.now();
        String reason = "private:" + "x".repeat(9000);
        for (String mode : List.of("complete", "retry", "abandon")) {
            repository.upsert(pending(mode));
            jobs.enqueue(mode, now);
            var lease = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
            boolean finished = switch (mode) {
                case "complete" -> jobs.complete(lease, pending(mode).failed("safe"), now, reason);
                case "retry" -> jobs.retry(lease, now.plusSeconds(100), reason, now);
                default -> jobs.abandon(lease, reason, now);
            };
            assertThat(finished).isTrue();
            assertThat(firestore.collection(collection + "-validation").document(mode).get().get().getString("lastError"))
                .hasSize(8000).isEqualTo(reason.substring(0, 8000));
        }
    }

    @Test void repairFencesLiveLeasesAndResetsTerminalJobs() throws Exception {
        var jobs = jobs();
        var event = pending("repair-live");
        repository.createMissing(List.of(event));
        var now = Instant.now().plusSeconds(1);
        var lease = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        var jobRef = firestore.collection(collection + "-validation").document(event.id());
        var gateRef = firestore.collection("_catalogWorkerLeases").document(collection);
        var gateBefore = gateRef.get().get().getData();
        var raw = new HashMap<>(FirestoreEventCatalogRepository.toDocument(event));
        raw.put("computedTimeUtc", "bad");
        firestore.collection(collection).document(event.id()).set(raw).get();
        assertThat(repository.createMissing(List.of(event)).created()).containsExactly(event);
        assertThat(jobRef.get().get().getString("state")).isEqualTo("ready");
        assertThat(jobRef.get().get().contains("token")).isFalse();
        assertThat(gateRef.get().get().getData()).isEqualTo(gateBefore);
        assertThat(jobs.complete(lease, event.validated(TIME, null, 180.0, null, 0.0, "ok"), now)).isFalse();
        var replacement = jobs.claim(now.plusSeconds(1), java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(replacement.eventId()).isEqualTo(event.id());
        assertThat(jobs.abandon(lease, "stale worker", now.plusSeconds(1))).isFalse();
        assertThat(jobs.claim(now.plusSeconds(1), java.time.Duration.ofMinutes(10))).isEmpty();
        assertThat(jobs.complete(replacement, event.validated(TIME, null, 180.0, null, 0.0, "ok"), now.plusSeconds(1))).isTrue();

        var terminal = pending("repair-terminal");
        repository.upsert(terminal);
        var terminalJob = firestore.collection(collection + "-validation").document(terminal.id());
        terminalJob.set(Map.of("state", "failed", "attempts", 12L, "retryExhausted", true)).get();
        var broken = new HashMap<>(FirestoreEventCatalogRepository.toDocument(terminal));
        broken.put("computedTimeUtc", "bad");
        firestore.collection(collection).document(terminal.id()).set(broken).get();
        assertThat(repository.createMissing(List.of(terminal)).created()).containsExactly(terminal);
        assertThat(terminalJob.get().get().getString("state")).isEqualTo("ready");
        assertThat(terminalJob.get().get().getLong("attempts")).isZero();
        assertThat(terminalJob.get().get().contains("retryExhausted")).isFalse();
        assertThat(jobs.claim(now.plusSeconds(2), java.time.Duration.ofMinutes(10)).orElseThrow().eventId()).isEqualTo(terminal.id());
    }

    @Test void recoveryPoisonsMalformedFlaggedJobsWithoutResettingALiveLease() throws Exception {
        var jobs = jobs();
        var now = Instant.now();
        repository.upsert(pending("z-live"));
        jobs.enqueue("z-live", now);
        var live = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        var liveRef = firestore.collection(collection + "-validation").document("z-live");
        liveRef.update("retryExhausted", true).get();
        var liveBefore = liveRef.get().get().getData();
        for (String id : List.of("a-numeric", "b-no-expiry", "c-good")) {
            repository.upsert(pending(id));
            firestore.collection(collection + "-validation").document(id).set(Map.of(
                "state", id.equals("a-numeric") ? 7L : id.equals("b-no-expiry") ? "leased" : "failed",
                "retryExhausted", true, "attempts", 12L)).get();
        }
        assertThat(jobs.requeueExhausted(null, 10, now).queued()).isEqualTo(1);
        for (String id : List.of("a-numeric", "b-no-expiry")) {
            var poisoned = firestore.collection(collection + "-validation").document(id).get().get();
            assertThat(poisoned.getString("state")).isEqualTo("failed");
            assertThat(poisoned.getBoolean("retryExhausted")).isFalse();
            assertThat(poisoned.getString("lastError")).contains("Malformed validation job during recovery");
        }
        assertThat(liveRef.get().get().getData()).isEqualTo(liveBefore);
        assertThat(jobs.requeueExhausted(null, 10, now).queued()).isZero();
        assertThat(jobs.complete(live, pending("z-live"), now)).isTrue();
        assertThat(jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow().eventId()).isEqualTo("c-good");
    }

    @Test void oneDrainPassesSixtyFivePoisonedJobsAndChecksStateBeforeClaiming() throws Exception {
        var now = Instant.now().minusSeconds(5);
        var batch = firestore.batch();
        for (int i = 0; i < 65; i++) {
            batch.set(firestore.collection(collection + "-validation").document("poison-" + i),
                Map.of("state", i % 2 == 0 ? "done" : "failed", "attempts", 0L,
                    "availableAt", FirestoreEventCatalogRepository.timestamp(now)));
        }
        batch.commit().get();
        var event = pending("good");
        repository.upsert(event);
        var jobs = jobs();
        jobs.enqueue("good", now.plusSeconds(1));
        var validation = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplValidationService.class);
        org.mockito.Mockito.when(validation.validateEvent("good")).thenReturn(event.validated(TIME, null, 180.0, null, 0.0, "ok"));
        assertThat(worker(jobs, validation).drainBatch()).isEqualTo(
            new dev.andreydg.solarsystem.jpl.ValidationWorker.DrainResult("idle-or-busy", 1));
        for (int i = 0; i < 65; i++) {
            var poisoned = firestore.collection(collection + "-validation").document("poison-" + i).get().get();
            assertThat(poisoned.getString("lastError")).startsWith("Malformed validation job");
            assertThat(poisoned.contains("availableAt")).isFalse();
        }
    }

    @Test void firestoreDeferralsCannotRefundAnUnfinishableJobForever() throws Exception {
        var event = pending("slow");
        repository.upsert(event);
        var jobs = jobs();
        var time = Instant.now();
        jobs.enqueue(event.id(), time);
        for (int i = 0; i < 36; i++) {
            var lease = jobs.claim(time, java.time.Duration.ofMinutes(10)).orElseThrow();
            assertThat(lease.attempt()).isEqualTo(i / 3 + 1);
            assertThat(jobs.defer(lease, time.plusSeconds(1), time)).isTrue();
            time = time.plusSeconds(2);
        }
        var exhausted = jobs.claim(time, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(exhausted.attempt()).isEqualTo(13);
        assertThat(jobs.abandon(exhausted, "Retry budget exhausted", time)).isTrue();
        assertThat(jobs.requeueExhausted(null, 10, time).queued()).isEqualTo(1);
    }


    @Test void repairedOrRecreatedEventsBetweenWorkerReadAndAbandonAreImmediatelyClaimable() throws Exception {
        for (boolean deleted : List.of(false, true)) {
            var event = pending(deleted ? "deleted-race" : "corrupt-race");
            repository.createMissing(List.of(event));
            var ref = firestore.collection(collection).document(event.id());
            if (deleted) ref.delete().get();
            else ref.update("computedTimeUtc", "broken").get();
            var catalog = org.mockito.Mockito.spy(new EventCatalogService(
                org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider.class), repository));
            org.mockito.Mockito.doAnswer(call -> {
                RuntimeException failure = null;
                Optional<CatalogEvent> found = Optional.empty();
                try { found = repository.findById(event.id()); } catch (RuntimeException caught) { failure = caught; }
                assertThat(repository.createMissing(List.of(event)).created()).containsExactly(event);
                if (failure != null) throw failure;
                return found;
            }).when(catalog).findById(event.id());
            var jobs = jobs();
            var validation = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplValidationService.class);
            var worker = new dev.andreydg.solarsystem.jpl.ValidationWorker(jobs, catalog, validation,
                new dev.andreydg.solarsystem.config.JplProperties("unused", true, 2, 3, 1));
            assertThat(worker.drainOne()).isEqualTo("lease-lost");
            org.mockito.Mockito.verifyNoInteractions(validation);
            assertThat(repository.findById(event.id())).contains(event);
            var replacement = jobs.claim(Instant.now(), java.time.Duration.ofMinutes(10)).orElseThrow();
            assertThat(replacement.eventId()).isEqualTo(event.id());
            assertThat(replacement.attempt()).isEqualTo(1);
            assertThat(jobs.complete(replacement, event.validated(TIME, null, 180.0, null, 0.0, "repaired"), Instant.now())).isTrue();
        }
    }

    @Test void successfulRetryClearsThePreviousPrivateError() throws Exception {
        var event = pending("retry-success");
        repository.createMissing(List.of(event));
        var jobs = jobs();
        var now = Instant.now().plusSeconds(1);
        var lease = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(jobs.retry(lease, now, "temporary failure", now)).isTrue();
        var ref = firestore.collection(collection + "-validation").document(event.id());
        assertThat(ref.get().get().getString("lastError")).isEqualTo("temporary failure");
        var retry = jobs.claim(now, java.time.Duration.ofMinutes(10)).orElseThrow();
        assertThat(jobs.complete(retry, event.validated(TIME, null, 180.0, null, 0.0, "ok"), now)).isTrue();
        assertThat(ref.get().get().getString("state")).isEqualTo("done");
        assertThat(ref.get().get().contains("lastError")).isFalse();
    }

    @Test void outageExhaustionRemainsRecoverableForPendingAndPreviouslyRejectedEvents() throws Exception {
        for (boolean rejected : List.of(false, true)) {
            String id = rejected ? "outage-rejected" : "outage-pending";
            var original = rejected ? pending(id).failed("Geometry rejected") : pending(id);
            repository.upsert(original);
            var jobs = jobs();
            jobs.enqueue(id, Instant.now().minusSeconds(1));
            firestore.collection(collection + "-validation").document(id).update("attempts", 11L).get();
            var validation = org.mockito.Mockito.mock(dev.andreydg.solarsystem.jpl.JplValidationService.class);
            org.mockito.Mockito.when(validation.validateEvent(id))
                .thenThrow(new dev.andreydg.solarsystem.jpl.JplHorizonsException("private upstream outage", true));
            assertThat(worker(jobs, validation).drainOne()).isEqualTo("failed");
            var stored = repository.findById(id).orElseThrow();
            if (rejected) assertThat(stored).isEqualTo(original);
            else {
                assertThat(stored.validationStatus()).isEqualTo(ValidationStatus.UNAVAILABLE);
                assertThat(stored.jplRawSummary()).isEqualTo(
                    "Retry budget exhausted: JPL is temporarily unavailable. An administrator can retry this event.");
            }
            var diagnostic = firestore.collection(collection + "-validation").document(id).get().get();
            assertThat(diagnostic.getBoolean("retryExhausted")).isTrue();
            assertThat(diagnostic.getString("lastError")).contains("private upstream outage");
            assertThat(jobs.requeueExhausted(null, 100, Instant.now()).queued()).isEqualTo(1);
            var lease = jobs.claim(Instant.now(), java.time.Duration.ofMinutes(10)).orElseThrow();
            assertThat(lease.eventId()).isEqualTo(id);
            assertThat(lease.attempt()).isEqualTo(1);
            jobs.abandon(lease, "test consumed recovered job", Instant.now());
        }
    }

    private FirestoreValidationJobs jobs() {
        return new FirestoreValidationJobs(firestore, new FirestoreProperties(collection, "(default)", System.getenv("FIRESTORE_EMULATOR_HOST")));
    }
    private dev.andreydg.solarsystem.jpl.ValidationWorker worker(ValidationJobs jobs, dev.andreydg.solarsystem.jpl.JplValidationService validation) {
        var catalog = new EventCatalogService(org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider.class), repository);
        return new dev.andreydg.solarsystem.jpl.ValidationWorker(jobs, catalog, validation,
            new dev.andreydg.solarsystem.config.JplProperties("unused", true, 2, 3, 1));
    }
    private CatalogEvent pending(String id) {
        return new CatalogEvent(id, EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS, TIME, null, 180.0, null,
            "TEST", null, null, null, null, null, ValidationStatus.PENDING,
            TIME.minusSeconds(100), TIME.plusSeconds(200), TIME, null, null, null);
    }

    @Test void unavailableCoverageIsReturnedByIndexedSearchButNotTheValidatedList() throws Exception {
        var event = event("no-coverage", TIME, BodyId.MARS).pending().unavailable("No ephemeris after 2500");
        repository.upsert(event);
        new FirestoreIndexMigration(firestore, collection).run(true);
        var query = new CatalogQuery(EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS,
            TIME.minusSeconds(10), TIME.plusSeconds(10), false, false, 10, null);
        assertThat(repository.search(query).events()).containsExactly(event);
        assertThat(repository.search(query(false, 10, null)).events()).isEmpty();
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

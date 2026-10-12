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
        firestore = FirestoreOptions.newBuilder().setProjectId("solar-system-test").setHost(host)
            .setCredentials(new EmulatorCredentials()).build().getService();
        collection = "events-" + UUID.randomUUID();
        repository = new FirestoreEventCatalogRepository(firestore, new FirestoreProperties(collection, "(default)", host));
    }
    @AfterEach void close() throws Exception { if (firestore != null) firestore.close(); }

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

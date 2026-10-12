package dev.andreydg.solarsystem.storage;

import com.google.cloud.firestore.*;
import java.util.Map;

/** Explicit, bounded, repeatable backfill. Updates only new index fields and uses write preconditions. */
public final class FirestoreIndexMigration {
    private final Firestore firestore;
    private final String collection;
    public FirestoreIndexMigration(Firestore firestore, String collection) {
        this.firestore = firestore;
        this.collection = collection;
    }
    public Result run(boolean apply) throws Exception {
        int scanned = 0, changed = 0, malformed = 0;
        DocumentSnapshot after = null;
        while (true) {
            Query query = firestore.collection(collection).orderBy(FieldPath.documentId()).limit(200);
            if (after != null) query = query.startAfter(after);
            var documents = query.get().get().getDocuments();
            if (documents.isEmpty()) break;
            var batch = firestore.batch();
            int writes = 0;
            for (var document : documents) {
                scanned++;
                Map<String, Object> fields;
                try {
                    var event = FirestoreEventCatalogRepository.fromDocument(document.getData());
                    if (!document.getId().equals(event.id())) throw new IllegalArgumentException("Mismatched event id");
                    fields = FirestoreEventCatalogRepository.indexFields(event);
                } catch (RuntimeException exception) {
                    malformed++;
                    System.err.println("Catalog migration: repair malformed record " + document.getId());
                    continue;
                }
                if (fields.entrySet().stream().allMatch(entry -> entry.getValue().equals(document.get(entry.getKey())))) continue;
                changed++;
                if (apply) {
                    batch.update(document.getReference(), fields, Precondition.updatedAt(document.getUpdateTime()));
                    writes++;
                }
            }
            if (writes > 0) batch.commit().get();
            after = documents.getLast();
        }
        if (apply && malformed == 0) {
            firestore.collection("_catalogMetadata").document(collection)
                .set(Map.of("indexedQueriesReady", true, "readSchemaVersion", 2)).get();
        }
        return new Result(scanned, changed, malformed, apply && malformed == 0);
    }
    public record Result(int scanned, int changed, int malformed, boolean ready) {}
}

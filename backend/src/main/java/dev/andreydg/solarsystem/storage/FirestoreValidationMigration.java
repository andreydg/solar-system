package dev.andreydg.solarsystem.storage;

import com.google.cloud.firestore.*;
import dev.andreydg.solarsystem.catalog.ValidationStatus;
import java.time.Instant;

/** One-time outbox repair for pre-outbox PENDING records. Never automatically retries FAILED jobs. */
public final class FirestoreValidationMigration {
    private final Firestore firestore;
    private final String collection;
    public FirestoreValidationMigration(Firestore firestore, String collection) { this.firestore = firestore; this.collection = collection; }
    public int run(boolean apply) throws Exception {
        int added = 0;
        DocumentSnapshot after = null;
        while (true) {
            Query query = firestore.collection(collection).orderBy(FieldPath.documentId()).limit(200);
            if (after != null) query = query.startAfter(after);
            var documents = query.get().get().getDocuments();
            if (documents.isEmpty()) return added;
            for (var event : documents) {
                if (!Long.valueOf(dev.andreydg.solarsystem.catalog.CatalogEvent.CURRENT_ALGORITHM_VERSION).equals(event.getLong("algorithmVersion"))) continue;
                if (ValidationStatus.fromStoredValue(event.get("validationStatus")) != ValidationStatus.PENDING) continue;
                boolean changed = firestore.runTransaction(tx -> {
                    var job = firestore.collection(collection + "-validation").document(event.getId());
                    var current = tx.getAll(event.getReference(), job).get();
                    if (current.get(1).exists() || !current.get(0).exists()
                        || ValidationStatus.fromStoredValue(current.get(0).get("validationStatus")) != ValidationStatus.PENDING) return false;
                    if (apply) tx.create(job, FirestoreValidationJobs.initial(Instant.now()));
                    return true;
                }).get();
                if (changed) added++;
            }
            after = documents.getLast();
        }
    }
}

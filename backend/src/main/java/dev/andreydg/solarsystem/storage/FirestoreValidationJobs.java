package dev.andreydg.solarsystem.storage;

import com.google.cloud.firestore.*;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.FirestoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import static dev.andreydg.solarsystem.storage.FirestoreEventCatalogRepository.*;

@Repository
@ConditionalOnProperty(name = "solar-system.storage", havingValue = "firestore", matchIfMissing = true)
public class FirestoreValidationJobs implements ValidationJobs {
    private final Firestore firestore;
    private final String collection;
    public FirestoreValidationJobs(Firestore firestore, FirestoreProperties properties) {
        this.firestore = firestore;
        this.collection = properties.collection();
    }
    static Map<String, Object> initial(Instant now) {
        return Map.of("availableAt", timestamp(now), "attempts", 0L, "state", "ready");
    }
    private DocumentReference job(String id) { return firestore.collection(collection + "-validation").document(id); }
    private DocumentReference gate() { return firestore.collection("_catalogWorkerLeases").document(collection); }

    @Override public Optional<Lease> claim(Instant now, Duration duration) {
        try {
            // One shared lease bounds background JPL work across ALL application instances.
            return firestore.runTransaction(tx -> {
                var gateSnapshot = tx.get(gate()).get();
                if (gateSnapshot.exists() && instant(gateSnapshot.getTimestamp("until")).isAfter(now)) return Optional.<Lease>empty();
                var due = tx.get(firestore.collection(collection + "-validation")
                    .whereLessThanOrEqualTo("availableAt", timestamp(now)).orderBy("availableAt").limit(1)).get().getDocuments();
                if (due.isEmpty()) return Optional.<Lease>empty();
                var document = due.getFirst();
                String token = UUID.randomUUID().toString();
                int attempt = Math.toIntExact(document.getLong("attempts")) + 1;
                var until = timestamp(now.plus(duration));
                tx.update(document.getReference(), Map.of("availableAt", until, "token", token,
                    "attempts", attempt, "state", "leased"));
                tx.set(gate(), Map.of("until", until, "token", token));
                return Optional.of(new Lease(document.getId(), token, attempt));
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new EventCatalogStorageException("Interrupted claiming validation", e);
        } catch (ExecutionException e) { throw new EventCatalogStorageException("Failed claiming validation", e); }
    }

    @Override public boolean complete(Lease lease, CatalogEvent result, Instant now) {
        if (!lease.eventId().equals(result.id())) throw new IllegalArgumentException("Lease/event mismatch");
        return finish(lease, now, tx -> {
            tx.set(firestore.collection(collection).document(result.id()), toDocument(result));
            tx.update(job(lease.eventId()), Map.of("state", result.validationStatus() == ValidationStatus.VALIDATED ? "done" : "failed",
                "availableAt", FieldValue.delete(), "finishedAt", timestamp(now)));
        });
    }
    @Override public boolean retry(Lease lease, Instant availableAt, String reason, Instant now) {
        return finish(lease, now, tx -> tx.update(job(lease.eventId()), Map.of("availableAt", timestamp(availableAt),
            "state", "ready", "lastError", reason)));
    }
    @Override public boolean abandon(Lease lease, String reason, Instant now) {
        return finish(lease, now, tx -> tx.update(job(lease.eventId()), Map.of("state", "failed", "lastError", reason,
            "availableAt", FieldValue.delete(), "finishedAt", timestamp(now))));
    }
    private boolean finish(Lease lease, Instant now, java.util.function.Consumer<Transaction> write) {
        try {
            return firestore.runTransaction(tx -> {
                var snapshots = tx.getAll(job(lease.eventId()), gate()).get();
                var job = snapshots.get(0); var gate = snapshots.get(1);
                if (!job.exists() || !gate.exists() || !"leased".equals(job.getString("state"))
                    || !lease.token().equals(job.getString("token")) || !lease.token().equals(gate.getString("token"))
                    || !instant(gate.getTimestamp("until")).isAfter(now)) return false;
                write.accept(tx);
                tx.set(gate(), Map.of("until", timestamp(now), "token", ""));
                return true;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new EventCatalogStorageException("Interrupted finishing validation", e);
        } catch (ExecutionException e) { throw new EventCatalogStorageException("Failed finishing validation", e); }
    }

    @Override public void enqueue(String id, Instant now) {
        try {
            firestore.runTransaction(tx -> {
                var eventRef = firestore.collection(collection).document(id);
                var docs = tx.getAll(eventRef, job(id)).get();
                if (!docs.get(0).exists()) throw new IllegalArgumentException("Catalog event not found");
                var prior = docs.get(1);
                if (prior.exists() && "leased".equals(prior.getString("state"))
                    && instant(prior.getTimestamp("availableAt")).isAfter(now)) {
                    throw new IllegalArgumentException("Validation is already running");
                }
                tx.set(eventRef, toDocument(fromDocument(docs.get(0).getData()).pending()));
                tx.set(job(id), initial(now));
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new EventCatalogStorageException("Interrupted queueing validation", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            throw new EventCatalogStorageException("Failed queueing validation", e);
        }
    }
}

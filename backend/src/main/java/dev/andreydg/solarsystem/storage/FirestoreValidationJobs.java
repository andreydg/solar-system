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

    private record ClaimPage(Optional<Lease> lease, boolean discardedPoison) {}
    @Override public Optional<Lease> claim(Instant now, Duration duration) {
        while (dev.andreydg.solarsystem.jpl.ValidationBudget.hasTime()) {
            var page = claimPage(now, duration);
            if (page.lease().isPresent() || !page.discardedPoison()) return page.lease();
        }
        return Optional.empty();
    }
    private ClaimPage claimPage(Instant now, Duration duration) {
        try {
            // One shared lease bounds background JPL work across ALL application instances.
            return firestore.runTransaction(tx -> {
                var gateSnapshot = tx.get(gate()).get();
                if (gateSnapshot.exists() && instant(gateSnapshot.getTimestamp("until")).isAfter(now)) return new ClaimPage(Optional.empty(), false);
                var due = tx.get(firestore.collection(collection + "-validation")
                    .whereLessThanOrEqualTo("availableAt", timestamp(now)).orderBy("availableAt").limit(20)).get().getDocuments();
                if (due.isEmpty()) return new ClaimPage(Optional.empty(), false);
                QueryDocumentSnapshot document = null;
                for (var candidate : due) {
                    try {
                        Long attempts = candidate.getLong("attempts");
                        Long deferrals = candidate.contains("deferrals") ? candidate.getLong("deferrals") : 0L;
                        if (deferrals == null || deferrals < 0 || deferrals > MAX_FREE_DEFERRALS) throw new IllegalArgumentException("Invalid deferrals");
                        if (attempts == null || attempts < 0 || attempts >= Integer.MAX_VALUE
                            || !Set.of("ready", "leased").contains(candidate.getString("state")))
                            throw new IllegalArgumentException("Invalid job fields");
                        document = candidate;
                        break;
                    } catch (RuntimeException malformed) {
                        tx.update(candidate.getReference(), Map.of("state", "failed", "retryExhausted", false,
                            "lastError", "Malformed validation job; repair before requeue",
                            "availableAt", FieldValue.delete(), "finishedAt", timestamp(now)));
                    }
                }
                if (document == null) return new ClaimPage(Optional.empty(), true);
                String token = UUID.randomUUID().toString();
                int attempt = Math.toIntExact(document.getLong("attempts")) + 1;
                var until = timestamp(now.plus(duration));
                tx.update(document.getReference(), Map.of("availableAt", until, "token", token,
                    "attempts", attempt, "state", "leased"));
                tx.set(gate(), Map.of("until", until, "token", token));
                return new ClaimPage(Optional.of(new Lease(document.getId(), token, attempt,
                    document.contains("deferrals") ? Math.toIntExact(document.getLong("deferrals")) : 0)), false);
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new EventCatalogStorageException("Interrupted claiming validation", e);
        } catch (ExecutionException e) { throw new EventCatalogStorageException("Failed claiming validation", e); }
    }

    @Override public boolean complete(Lease lease, CatalogEvent result, Instant now) {
        return complete(lease, result, now, null);
    }
    @Override public boolean complete(Lease lease, CatalogEvent result, Instant now, String privateError) {
        if (!lease.eventId().equals(result.id())) throw new IllegalArgumentException("Lease/event mismatch");
        return finish(lease, now, tx -> {
            tx.set(firestore.collection(collection).document(result.id()), toDocument(result));
            tx.update(job(lease.eventId()), "lastError", privateError == null ? FieldValue.delete() : ValidationJobs.privateDiagnostic(privateError));
            tx.update(job(lease.eventId()), Map.of("state", result.validationStatus() == ValidationStatus.VALIDATED ? "done" : "failed",
                "availableAt", FieldValue.delete(), "finishedAt", timestamp(now)));
        });
    }
    @Override public boolean defer(Lease lease, Instant availableAt, Instant now) {
        return finish(lease, now, tx -> tx.update(job(lease.eventId()), Map.of("availableAt", timestamp(availableAt),
            "state", "ready", "attempts", lease.attemptsAfterDeferral(), "deferrals", lease.deferralsAfterDeferral(), "lastError", "Worker time budget exhausted")));
    }
    @Override public boolean retry(Lease lease, Instant availableAt, String reason, Instant now) {
        return finish(lease, now, tx -> tx.update(job(lease.eventId()), Map.of("availableAt", timestamp(availableAt),
            "state", "ready", "deferrals", 0, "lastError", ValidationJobs.privateDiagnostic(reason))));
    }
    @Override public boolean abandon(Lease lease, String reason, Instant now) {
        return finish(lease, now, tx -> tx.update(job(lease.eventId()), Map.of("state", "failed", "lastError", ValidationJobs.privateDiagnostic(reason), "retryExhausted", reason.startsWith("Retry budget exhausted"),
            "availableAt", FieldValue.delete(), "finishedAt", timestamp(now))));
    }
    private boolean finish(Lease lease, Instant now, java.util.function.Consumer<Transaction> write) {
        try {
            return firestore.runTransaction(tx -> {
                var snapshots = tx.getAll(job(lease.eventId()), gate()).get();
                var job = snapshots.get(0); var gate = snapshots.get(1);
                if (!job.exists() || !gate.exists() || !"leased".equals(job.getString("state"))
                    || !lease.token().equals(job.getString("token")) || !lease.token().equals(gate.getString("token"))
                    || !instant(gate.getTimestamp("until")).isAfter(now)) {
                    // A repair may have reset the job, but only this lease may release its gate.
                    if (gate.exists() && lease.token().equals(gate.getString("token")))
                        tx.set(gate(), Map.of("until", timestamp(now), "token", ""));
                    return false;
                }
                write.accept(tx);
                tx.set(gate(), Map.of("until", timestamp(now), "token", ""));
                return true;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new EventCatalogStorageException("Interrupted finishing validation", e);
        } catch (ExecutionException e) { throw new EventCatalogStorageException("Failed finishing validation", e); }
    }

    @Override public RecoveryPage requeueExhausted(String after, int limit, Instant now) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Recovery limit must be 1–100");
        if (after != null && (!after.matches("[A-Za-z0-9_:-]{1,180}") || after.matches("__.*__")))
            throw new IllegalArgumentException("Invalid recovery cursor");
        try {
            Query query = firestore.collection(collection + "-validation").whereEqualTo("retryExhausted", true)
                .orderBy(FieldPath.documentId()).limit(limit + 1);
            if (after != null) query = query.startAfter(job(after));
            var documents = query.get().get().getDocuments();
            var page = documents.subList(0, Math.min(limit, documents.size()));
            int queued = 0;
            for (var document : page) if (recoverOne(document.getId(), now)) queued++;
            return new RecoveryPage(queued, documents.size() > limit ? page.getLast().getId() : null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new EventCatalogStorageException("Interrupted recovering jobs", e);
        } catch (ExecutionException e) { throw new EventCatalogStorageException("Failed recovering jobs", e); }
    }

    private boolean recoverOne(String id, Instant now) throws InterruptedException, ExecutionException {
        return firestore.runTransaction(tx -> {
            var docs = tx.getAll(firestore.collection(collection).document(id), job(id)).get();
            var prior = docs.get(1);
            if (!prior.exists() || !Boolean.TRUE.equals(prior.getBoolean("retryExhausted"))) return false;
            if (!docs.get(0).exists()) {
                tx.update(job(id), Map.of("retryExhausted", false, "state", "failed",
                    "lastError", "Catalog event missing; skipped recovery", "availableAt", FieldValue.delete()));
                return false;
            }
            try {
                String state = prior.getString("state");
                if (state == null || !Set.of("ready", "leased", "done", "failed").contains(state))
                    throw new IllegalArgumentException("Invalid job state");
                if ("leased".equals(state)) {
                    var available = prior.getTimestamp("availableAt");
                    if (available == null) throw new IllegalArgumentException("Leased job has no expiry");
                    if (instant(available).isAfter(now)) return false;
                }
            } catch (RuntimeException malformed) {
                tx.update(job(id), Map.of("retryExhausted", false, "state", "failed",
                    "lastError", ValidationJobs.privateDiagnostic("Malformed validation job during recovery: " + malformed),
                    "availableAt", FieldValue.delete(), "finishedAt", timestamp(now)));
                return false;
            }
            tx.set(job(id), initial(now));
            return true;
        }).get();
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

package dev.andreydg.solarsystem.storage;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.Timestamp;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventType;
import dev.andreydg.solarsystem.catalog.ValidationStatus;
import dev.andreydg.solarsystem.config.FirestoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "solar-system.storage", havingValue = "firestore", matchIfMissing = true)
public class FirestoreEventCatalogRepository implements EventCatalogRepository {
    private static final Logger log = LoggerFactory.getLogger(FirestoreEventCatalogRepository.class);
    // Transactions may write an event, a quarantine copy, and (in the worker layer) a job.
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_QUERY_SCAN = 2000;

    private final Firestore firestore;
    private final String collection;
    private volatile boolean indexedSchemaReady;

    public FirestoreEventCatalogRepository(Firestore firestore, FirestoreProperties properties) {
        this.firestore = firestore;
        this.collection = properties.collection();
    }

    private record Repair(com.google.cloud.firestore.DocumentSnapshot original,
        com.google.cloud.firestore.DocumentReference copy, String reason) {}
    private record CreationReceipt(WriteResult result, List<Repair> notices) {}

    @Override
    public WriteResult createMissing(List<CatalogEvent> events) {
        List<CatalogEvent> stored = new ArrayList<>();
        List<CatalogEvent> created = new ArrayList<>();
        try {
            for (int start = 0; start < events.size(); start += MAX_BATCH_SIZE) {
                List<CatalogEvent> chunk = events.subList(start, Math.min(start + MAX_BATCH_SIZE, events.size()));
                var receipt = firestore.runTransaction(transaction -> {
                    var refs = chunk.stream().map(event -> firestore.collection(collection).document(event.id())).toList();
                    var snapshots = transaction.getAll(refs.toArray(com.google.cloud.firestore.DocumentReference[]::new)).get();
                    var actual = new ArrayList<CatalogEvent>();
                    var inserted = new ArrayList<CatalogEvent>();
                    var repairs = new ArrayList<Repair>();
                    for (int i = 0; i < chunk.size(); i++) {
                        var snapshot = snapshots.get(i);
                        if (!snapshot.exists()) { inserted.add(chunk.get(i)); actual.add(chunk.get(i)); continue; }
                        try { actual.add(fromDocument(snapshot.getData())); }
                        catch (RuntimeException malformed) {
                            String version = snapshot.getUpdateTime().getSeconds() + "-" + snapshot.getUpdateTime().getNanos();
                            var copy = refs.get(i).collection("_corrupt").document(version);
                            repairs.add(new Repair(snapshot, copy, malformed.toString()));
                            if (!hasAcceptedValues(snapshot.getData())) { inserted.add(chunk.get(i)); actual.add(chunk.get(i)); }
                        }
                    }
                    // Read all quarantine versions before any transaction writes. Existing copies
                    // are immutable and must not generate repeated writes or warnings.
                    var copies = repairs.isEmpty() ? List.<com.google.cloud.firestore.DocumentSnapshot>of()
                        : transaction.getAll(repairs.stream().map(Repair::copy)
                            .toArray(com.google.cloud.firestore.DocumentReference[]::new)).get();
                    var notices = new ArrayList<Repair>();
                    for (int i = 0; i < repairs.size(); i++) if (!copies.get(i).exists()) {
                        var repair = repairs.get(i);
                        transaction.create(repair.copy(), repair.original().getData());
                        notices.add(repair);
                    }
                    for (var event : inserted) {
                        transaction.set(firestore.collection(collection).document(event.id()), toDocument(event));
                        // A replacement must get a fresh job even if the old worker is still leased.
                        // Its token is fenced; finish() releases only that worker's own gate.
                        if (event.validationStatus() == ValidationStatus.PENDING)
                            transaction.set(firestore.collection(collection + "-validation").document(event.id()),
                                FirestoreValidationJobs.initial(Instant.now()));
                    }
                    return new CreationReceipt(new WriteResult(List.copyOf(actual), List.copyOf(inserted)), notices);
                }).get();
                // Log only committed new quarantine copies, never transaction retries.
                for (var repair : receipt.notices()) log.warn("Quarantining malformed catalog record {}: {}",
                    repair.original().getId(), repair.reason());
                stored.addAll(receipt.result().stored());
                created.addAll(receipt.result().created());
            }
            return new WriteResult(List.copyOf(stored), List.copyOf(created));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventCatalogStorageException("Interrupted while writing events", e);
        } catch (ExecutionException e) {
            throw new EventCatalogStorageException("Failed to write events", e);
        }
    }

    @Override
    public void upsert(CatalogEvent event) {
        try {
            firestore.collection(collection)
                .document(event.id())
                .set(toDocument(event))
                .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventCatalogStorageException("Interrupted while writing event " + event.id(), e);
        } catch (ExecutionException e) {
            throw new EventCatalogStorageException("Failed to write event " + event.id(), e);
        }
    }

    @Override
    public CatalogPage search(CatalogQuery criteria) {
        int scanned = 0;
        int malformedCount = 0;
        var malformedIds = new ArrayList<String>();
        String outcome = "aborted";
        try {
            requireIndexedSchema();
            Query query = firestore.collection(collection).whereEqualTo("algorithmVersion", CatalogEvent.CURRENT_ALGORITHM_VERSION);
            if (criteria.type() != null) query = query.whereEqualTo("type", criteria.type().apiValue());
            if (criteria.validatedOnly()) query = query.whereEqualTo("validationStatus", ValidationStatus.VALIDATED.storedValue());
            else query = query.whereIn("validationStatus", List.of("pending", "validated", "unavailable"));
            if (criteria.bodyA() != null && criteria.bodyB() != null) {
                query = query.whereEqualTo("pairKey", CatalogQuery.pairKey(criteria.bodyA(), criteria.bodyB()));
            } else if (criteria.bodyA() != null || criteria.bodyB() != null) {
                query = query.whereArrayContains("bodies", (criteria.bodyA() == null ? criteria.bodyB() : criteria.bodyA()).apiValue());
            }
            var direction = criteria.descending() ? Query.Direction.DESCENDING : Query.Direction.ASCENDING;
            query = query.whereGreaterThanOrEqualTo("displayTime", timestamp(criteria.from()))
                .whereLessThanOrEqualTo("displayTime", timestamp(criteria.to()))
                .orderBy("displayTime", direction).orderBy(FieldPath.documentId(), direction);
            if (criteria.cursor() != null && !criteria.cursor().isBlank()) {
                var cursor = CatalogQuery.decode(criteria.cursor());
                query = query.startAfter(timestamp(cursor.time()), firestore.collection(collection).document(cursor.id()));
            }
            // Fill the requested page on the server, not via a chain of empty browser GETs.
            // Bound the reads even if a collection has suffered widespread corruption.
            var valid = new ArrayList<CatalogEvent>();
            com.google.cloud.firestore.DocumentSnapshot lastValid = null;
            com.google.cloud.firestore.DocumentSnapshot lastScanned = null;
            while (scanned < MAX_QUERY_SCAN) {
                int batchSize = Math.min(Math.max(scanned == 0 ? 1 : 32, criteria.limit() + 1 - valid.size()), MAX_QUERY_SCAN - scanned);
                var documents = query.limit(batchSize).get().get().getDocuments();
                scanned += documents.size();
                for (var document : documents) {
                    lastScanned = document;
                    try {
                        var event = fromDocument(document.getData());
                        if (criteria.matches(event)) {
                            valid.add(event);
                            if (valid.size() <= criteria.limit()) lastValid = document;
                        }
                    } catch (RuntimeException malformed) {
                        malformedCount++;
                        if (malformedIds.size() < 5) malformedIds.add(document.getId());
                    }
                    if (valid.size() > criteria.limit()) {
                        outcome = "complete";
                        return new CatalogPage(List.copyOf(valid.subList(0, criteria.limit())), cursor(lastValid));
                    }
                }
                if (documents.size() < batchSize) {
                    outcome = "complete";
                    return new CatalogPage(List.copyOf(valid), null);
                }
                query = query.startAfter(documents.getLast());
            }
            if (!valid.isEmpty()) {
                outcome = "partial page at scan limit";
                return new CatalogPage(List.copyOf(valid), cursor(lastScanned));
            }
            outcome = "503 operator repair required";
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Catalog contains too many malformed records; operator repair required");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventCatalogStorageException("Interrupted while querying events", e);
        } catch (ExecutionException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.google.api.gax.rpc.ApiException api
                    && api.getStatusCode().getCode() == com.google.api.gax.rpc.StatusCode.Code.FAILED_PRECONDITION) {
                    log.warn("Catalog index query is not ready", e);
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Catalog indexes are not ready; retry after index deployment", e);
                }
            }
            throw new EventCatalogStorageException("Failed to query events", e);
        } finally {
            if (malformedCount > 0 || outcome.startsWith("503")) log.warn(
                "Catalog scan skipped {} malformed documents; first IDs {}; scanned {}; outcome {}",
                malformedCount, malformedIds, scanned, outcome);
        }
    }

    private static String cursor(com.google.cloud.firestore.DocumentSnapshot snapshot) {
        return CatalogQuery.encode(instant(snapshot.getTimestamp("displayTime")), snapshot.getId());
    }

    @Override
    public Optional<CatalogEvent> findById(String id) {
        try {
            var snapshot = firestore.collection(collection).document(id).get().get();
            return snapshot.exists() ? Optional.of(fromDocument(snapshot.getData())) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventCatalogStorageException("Interrupted while reading event " + id, e);
        } catch (ExecutionException e) {
            throw new EventCatalogStorageException("Failed to read event " + id, e);
        }
    }

    private void requireIndexedSchema() throws InterruptedException, ExecutionException {
        if (!indexedSchemaReady) {
            var metadata = firestore.collection("_catalogMetadata").document(collection).get().get();
            if (!Boolean.TRUE.equals(metadata.getBoolean("indexedQueriesReady")) || !Long.valueOf(2).equals(metadata.getLong("readSchemaVersion"))) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Catalog index migration required; see the deployment runbook");
            }
            indexedSchemaReady = true;
        }
    }

    private static boolean hasAcceptedValues(Map<String, Object> raw) {
        if ("validated".equalsIgnoreCase(String.valueOf(raw.get("validationStatus")).trim())) return true;
        // Also preserve accepted evidence when the status itself is damaged or missing.
        return java.util.stream.Stream.of("validatedTimeUtc", "validatedSource", "validatedDistanceAu",
            "validatedAngleDeg", "validatedMagnitude").anyMatch(key -> raw.get(key) != null);
    }

    static Map<String, Object> indexFields(CatalogEvent event) {
        return Map.of("displayTime", timestamp(event.displayTimeUtc()),
            "pairKey", CatalogQuery.pairKey(event.bodyA(), event.bodyB()),
            "bodies", List.of(event.bodyA().apiValue(), event.bodyB().apiValue()), "readSchemaVersion", 2L,
            "algorithmVersion", (long) event.algorithmVersion());
    }

    static Map<String, Object> toDocument(CatalogEvent event) {
        Map<String, Object> document = new HashMap<>();
        document.putAll(indexFields(event));
        document.put("id", event.id());
        document.put("type", event.type().apiValue());
        document.put("bodyA", event.bodyA().apiValue());
        document.put("bodyB", event.bodyB().apiValue());
        // Denormalized so it can back a server-side time-range index/query as the catalog grows.
        document.put("displayTimeUtc", event.displayTimeUtc().toString());
        document.put("computedTimeUtc", event.computedTimeUtc().toString());
        document.put("computedDistanceAu", event.computedDistanceAu());
        document.put("computedAngleDeg", event.computedAngleDeg());
        document.put("computedMagnitude", event.computedMagnitude());
        document.put("computedSource", event.computedSource());
        document.put("validatedTimeUtc", event.validatedTimeUtc() == null ? null : event.validatedTimeUtc().toString());
        document.put("validatedDistanceAu", event.validatedDistanceAu());
        document.put("validatedAngleDeg", event.validatedAngleDeg());
        document.put("validatedMagnitude", event.validatedMagnitude());
        document.put("validatedSource", event.validatedSource());
        document.put("validationStatus", event.validationStatus().storedValue());
        document.put("rangeStartUtc", event.rangeStartUtc().toString());
        document.put("rangeEndUtc", event.rangeEndUtc().toString());
        document.put("generatedAtUtc", event.generatedAtUtc().toString());
        document.put("jplCheckedAtUtc", event.jplCheckedAtUtc() == null ? null : event.jplCheckedAtUtc().toString());
        document.put("jplDeltaKm", event.jplDeltaKm());
        document.put("jplRawSummary", event.jplRawSummary());
        return document;
    }

    static CatalogEvent fromDocument(Map<String, Object> document) {
        return new CatalogEvent(
            (String) document.get("id"),
            EventType.fromApiValue((String) document.get("type")),
            BodyId.fromApiValue((String) document.get("bodyA")),
            BodyId.fromApiValue((String) document.get("bodyB")),
            parseRequiredInstant(document, "computedTimeUtc", "timeUtc"),
            parseOptionalDouble(document.getOrDefault("computedDistanceAu", document.get("distanceAu"))),
            parseOptionalDouble(document.getOrDefault("computedAngleDeg", document.get("angleDeg"))),
            parseOptionalDouble(document.getOrDefault("computedMagnitude", document.get("magnitude"))),
            (String) document.getOrDefault("computedSource", document.get("source")),
            parseOptionalInstant(document.get("validatedTimeUtc")),
            parseOptionalDouble(document.get("validatedDistanceAu")),
            parseOptionalDouble(document.get("validatedAngleDeg")),
            parseOptionalDouble(document.get("validatedMagnitude")),
            (String) document.get("validatedSource"),
            ValidationStatus.fromStoredValue(document.get("validationStatus")),
            java.util.Objects.requireNonNull(parseOptionalInstant(document.get("rangeStartUtc"))),
            java.util.Objects.requireNonNull(parseOptionalInstant(document.get("rangeEndUtc"))),
            java.util.Objects.requireNonNull(parseOptionalInstant(document.get("generatedAtUtc"))),
            parseOptionalInstant(document.get("jplCheckedAtUtc")),
            parseOptionalDouble(document.get("jplDeltaKm")),
            (String) document.get("jplRawSummary"),
            ((Number) document.getOrDefault("algorithmVersion", 0)).intValue()
        );
    }

    private static Instant parseOptionalInstant(Object value) {
        return value == null ? null : value instanceof Timestamp timestamp ? instant(timestamp) : Instant.parse(value.toString());
    }

    private static Instant parseRequiredInstant(Map<String, Object> document, String preferredKey, String fallbackKey) {
        Object value = document.getOrDefault(preferredKey, document.get(fallbackKey));
        return java.util.Objects.requireNonNull(parseOptionalInstant(value));
    }

    private static Double parseOptionalDouble(Object value) {
        if (value == null) return null;
        double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite catalog number");
        return number;
    }

    static Timestamp timestamp(Instant time) {
        return Timestamp.ofTimeSecondsAndNanos(time.getEpochSecond(), time.getNano());
    }
    static Instant instant(Timestamp time) { return Instant.ofEpochSecond(time.getSeconds(), time.getNanos()); }
}

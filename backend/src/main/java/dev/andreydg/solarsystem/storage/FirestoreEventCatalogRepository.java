package dev.andreydg.solarsystem.storage;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
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
import java.util.Comparator;
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
    // Firestore caps a batched write at 500 operations; stay under it with margin.
    private static final int MAX_BATCH_SIZE = 450;

    private final Firestore firestore;
    private final String collection;
    private volatile boolean indexedSchemaReady;

    public FirestoreEventCatalogRepository(Firestore firestore, FirestoreProperties properties) {
        this.firestore = firestore;
        this.collection = properties.collection();
    }

    @Override
    public void upsertAll(List<CatalogEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        try {
            // Commit in batches instead of one round-trip per document.
            for (int start = 0; start < events.size(); start += MAX_BATCH_SIZE) {
                List<CatalogEvent> chunk = events.subList(start, Math.min(start + MAX_BATCH_SIZE, events.size()));
                WriteBatch batch = firestore.batch();
                for (CatalogEvent event : chunk) {
                    batch.set(firestore.collection(collection).document(event.id()), toDocument(event));
                }
                batch.commit().get();
            }
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
        try {
            requireIndexedSchema();
            Query query = firestore.collection(collection);
            if (criteria.type() != null) query = query.whereEqualTo("type", criteria.type().apiValue());
            if (criteria.validatedOnly()) query = query.whereEqualTo("validationStatus", ValidationStatus.VALIDATED.storedValue());
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
            var documents = query.limit(criteria.limit() + 1).get().get().getDocuments();
            var page = documents.subList(0, Math.min(criteria.limit(), documents.size()));
            String next = null;
            if (documents.size() > criteria.limit()) {
                var last = page.getLast();
                next = CatalogQuery.encode(instant(last.getTimestamp("displayTime")), last.getId());
            }
            return new CatalogPage(toEventsSkippingMalformed(page), next);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventCatalogStorageException("Interrupted while querying events", e);
        } catch (ExecutionException e) {
            throw new EventCatalogStorageException("Failed to query events", e);
        }
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
            if (!Boolean.TRUE.equals(metadata.getBoolean("indexedQueriesReady"))) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Catalog index migration required; see the deployment runbook");
            }
            indexedSchemaReady = true;
        }
    }

    private List<CatalogEvent> toEventsSkippingMalformed(List<QueryDocumentSnapshot> documents) {
        List<CatalogEvent> events = new ArrayList<>(documents.size());
        for (QueryDocumentSnapshot snapshot : documents) {
            try {
                events.add(fromDocument(snapshot.getData()));
            } catch (RuntimeException exception) {
                // A single corrupt or legacy-shaped document must not fail the whole query.
                log.warn("Skipping malformed catalog document {}: {}", snapshot.getId(), exception.toString());
            }
        }
        return events;
    }

    static Map<String, Object> indexFields(CatalogEvent event) {
        return Map.of("displayTime", timestamp(event.displayTimeUtc()),
            "pairKey", CatalogQuery.pairKey(event.bodyA(), event.bodyB()),
            "bodies", List.of(event.bodyA().apiValue(), event.bodyB().apiValue()), "readSchemaVersion", 1L);
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
            Instant.parse((String) document.get("rangeStartUtc")),
            Instant.parse((String) document.get("rangeEndUtc")),
            Instant.parse((String) document.get("generatedAtUtc")),
            parseOptionalInstant(document.get("jplCheckedAtUtc")),
            (Double) document.get("jplDeltaKm"),
            (String) document.get("jplRawSummary")
        );
    }

    private static Instant parseOptionalInstant(Object value) {
        return value == null ? null : Instant.parse((String) value);
    }

    private static Instant parseRequiredInstant(Map<String, Object> document, String preferredKey, String fallbackKey) {
        Object value = document.getOrDefault(preferredKey, document.get(fallbackKey));
        return Instant.parse((String) value);
    }

    private static Double parseOptionalDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    static Timestamp timestamp(Instant time) {
        return Timestamp.ofTimeSecondsAndNanos(time.getEpochSecond(), time.getNano());
    }
    static Instant instant(Timestamp time) { return Instant.ofEpochSecond(time.getSeconds(), time.getNanos()); }
}

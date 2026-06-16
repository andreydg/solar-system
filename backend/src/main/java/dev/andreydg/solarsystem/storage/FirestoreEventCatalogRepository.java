package dev.andreydg.solarsystem.storage;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
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
    public List<CatalogEvent> find(
        EventType type,
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to
    ) {
        BodyIdOrdered ordered = getStoredOrder(type, bodyA, bodyB);
        try {
            var documents = firestore.collection(collection)
                .whereEqualTo("type", type.apiValue())
                .whereEqualTo("bodyA", ordered.bodyA().apiValue())
                .whereEqualTo("bodyB", ordered.bodyB().apiValue())
                .get()
                .get()
                .getDocuments();

            return toEventsSkippingMalformed(documents).stream()
                .filter(event -> !event.displayTimeUtc().isBefore(from) && !event.displayTimeUtc().isAfter(to))
                .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
                .toList();
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

    @Override
    public List<CatalogEvent> findAllValidated() {
        try {
            var documents = firestore.collection(collection)
                .whereEqualTo("validationStatus", ValidationStatus.VALIDATED.storedValue())
                .get()
                .get()
                .getDocuments();

            return toEventsSkippingMalformed(documents).stream()
                .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
                .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventCatalogStorageException("Interrupted while querying validated events", e);
        } catch (ExecutionException e) {
            throw new EventCatalogStorageException("Failed to query validated events", e);
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

    private static boolean matchesPair(CatalogEvent event, BodyId bodyA, BodyId bodyB) {
        return event.bodyA() == bodyA && event.bodyB() == bodyB
            || event.bodyA() == bodyB && event.bodyB() == bodyA;
    }

    private static Map<String, Object> toDocument(CatalogEvent event) {
        Map<String, Object> document = new HashMap<>();
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

    private static CatalogEvent fromDocument(Map<String, Object> document) {
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

    private static BodyIdOrdered getStoredOrder(EventType type, BodyId bodyA, BodyId bodyB) {
        if (type == EventType.CLOSEST_APPROACH || type == EventType.FARTHEST_APPROACH) {
            return bodyA.ordinal() <= bodyB.ordinal()
                ? new BodyIdOrdered(bodyA, bodyB)
                : new BodyIdOrdered(bodyB, bodyA);
        } else {
            BodyId target = bodyA == BodyId.EARTH ? bodyB : bodyA;
            return new BodyIdOrdered(BodyId.EARTH, target);
        }
    }

    private record BodyIdOrdered(BodyId bodyA, BodyId bodyB) {}
}

package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventType;
import dev.andreydg.solarsystem.catalog.ValidationStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "solar-system.storage", havingValue = "in-memory")
public class InMemoryEventCatalogRepository implements EventCatalogRepository {
    private final ConcurrentMap<String, CatalogEvent> events = new ConcurrentHashMap<>();

    @Override
    public void upsertAll(List<CatalogEvent> events) {
        events.forEach(this::upsert);
    }

    @Override
    public List<CatalogEvent> find(EventType type, BodyId bodyA, BodyId bodyB, Instant from, Instant to) {
        return events.values()
            .stream()
            .filter(event -> event.type() == type)
            .filter(event -> matchesPair(event, bodyA, bodyB))
            .filter(event -> !event.displayTimeUtc().isBefore(from) && !event.displayTimeUtc().isAfter(to))
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    @Override
    public Optional<CatalogEvent> findById(String id) {
        return Optional.ofNullable(events.get(id));
    }

    @Override
    public List<CatalogEvent> findAllValidated() {
        return events.values()
            .stream()
            .filter(event -> event.validationStatus() == ValidationStatus.VALIDATED)
            .sorted(Comparator.comparing(CatalogEvent::displayTimeUtc))
            .toList();
    }

    @Override
    public void upsert(CatalogEvent event) {
        events.put(event.id(), event);
    }

    private static boolean matchesPair(CatalogEvent event, BodyId bodyA, BodyId bodyB) {
        return event.bodyA() == bodyA && event.bodyB() == bodyB
            || event.bodyA() == bodyB && event.bodyB() == bodyA;
    }
}

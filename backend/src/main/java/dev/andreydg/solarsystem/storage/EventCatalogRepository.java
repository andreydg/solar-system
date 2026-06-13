package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventCatalogRepository {
    void upsertAll(List<CatalogEvent> events);

    List<CatalogEvent> find(
        EventType type,
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to
    );

    Optional<CatalogEvent> findById(String id);

    List<CatalogEvent> findAllValidated();

    void upsert(CatalogEvent event);
}

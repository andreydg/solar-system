package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventType;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventCatalogRepository {
    /** Create missing events without overwriting existing validation; return stored records. */
    List<CatalogEvent> upsertAll(List<CatalogEvent> events);

    default List<CatalogEvent> find(
        EventType type,
        BodyId bodyA,
        BodyId bodyB,
        Instant from,
        Instant to
    ) {
        return search(new CatalogQuery(type, bodyA, bodyB, from, to, false, false, 1000, null)).events();
    }

    Optional<CatalogEvent> findById(String id);

    CatalogPage search(CatalogQuery query);

    void upsert(CatalogEvent event);
}

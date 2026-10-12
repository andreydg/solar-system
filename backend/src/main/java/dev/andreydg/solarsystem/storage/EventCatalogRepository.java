package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
import java.util.List;
import java.util.Optional;

public interface EventCatalogRepository {
    /** Insert generated events and jobs atomically; return existing records instead of overwriting them. */
    List<CatalogEvent> upsertAll(List<CatalogEvent> events);

    Optional<CatalogEvent> findById(String id);

    CatalogPage search(CatalogQuery query);

    void upsert(CatalogEvent event);
}

package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
import java.util.List;
import java.util.Optional;

public interface EventCatalogRepository {
    /** Create missing events without overwriting existing validation; return stored records. */
    List<CatalogEvent> upsertAll(List<CatalogEvent> events);

    Optional<CatalogEvent> findById(String id);

    CatalogPage search(CatalogQuery query);

    void upsert(CatalogEvent event);
}

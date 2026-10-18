package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
import java.util.List;
import java.util.Optional;

public interface EventCatalogRepository {
    /** A transaction receipt: only this call's newly created/repaired records may schedule work. */
    WriteResult createMissing(List<CatalogEvent> events);
    record WriteResult(List<CatalogEvent> stored, List<CatalogEvent> created) {}

    Optional<CatalogEvent> findById(String id);

    CatalogPage search(CatalogQuery query);

    void upsert(CatalogEvent event);
}

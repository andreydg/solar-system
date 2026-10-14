package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventType;
import dev.andreydg.solarsystem.catalog.ValidationStatus;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
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
    public List<CatalogEvent> upsertAll(List<CatalogEvent> events) {
        return events.stream().map(event -> this.events.computeIfAbsent(event.id(), id -> event)).toList();
    }

    @Override
    public CatalogPage search(CatalogQuery query) {
        var cursor = query.cursor() == null || query.cursor().isBlank() ? null : CatalogQuery.decode(query.cursor());
        var found = events.values().stream().filter(query::matches).sorted(query.ordering())
            .filter(event -> {
                if (cursor == null) return true;
                int order = event.displayTimeUtc().compareTo(cursor.time());
                if (order == 0) order = event.id().compareTo(cursor.id());
                return query.descending() ? order < 0 : order > 0;
            }).limit(query.limit() + 1L).toList();
        var page = found.stream().limit(query.limit()).toList();
        String next = found.size() > query.limit()
            ? CatalogQuery.encode(page.getLast().displayTimeUtc(), page.getLast().id()) : null;
        return new CatalogPage(page, next);
    }

    @Override
    public Optional<CatalogEvent> findById(String id) {
        return Optional.ofNullable(events.get(id));
    }

    @Override
    public void upsert(CatalogEvent event) {
        events.put(event.id(), event);
    }

}

package dev.andreydg.solarsystem.storage;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventType;
import dev.andreydg.solarsystem.catalog.ValidationStatus;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
import dev.andreydg.solarsystem.catalog.ValidationJobs;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
public class InMemoryEventCatalogRepository implements EventCatalogRepository, ValidationJobs {
    private final ConcurrentMap<String, CatalogEvent> events = new ConcurrentHashMap<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private final java.util.Set<String> exhausted = new java.util.HashSet<>();
    private Lease activeLease;
    private Instant leaseUntil = Instant.MIN;

    @Override
    public synchronized List<CatalogEvent> upsertAll(List<CatalogEvent> events) {
        var stored = new java.util.ArrayList<CatalogEvent>();
        for (var event : events) {
            if (this.events.putIfAbsent(event.id(), event) == null && event.validationStatus() == ValidationStatus.PENDING) {
                jobs.put(event.id(), new Job(0, Instant.EPOCH, null));
            }
            stored.add(this.events.get(event.id()));
        }
        return List.copyOf(stored);
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

    @Override public synchronized Optional<Lease> claim(Instant now, Duration duration) {
        if (leaseUntil.isAfter(now)) return Optional.empty();
        var due = jobs.entrySet().stream().filter(entry -> !entry.getValue().availableAt().isAfter(now))
            .min(Comparator.comparing(entry -> entry.getValue().availableAt()));
        if (due.isEmpty()) return Optional.empty();
        var entry = due.get();
        activeLease = new Lease(entry.getKey(), UUID.randomUUID().toString(), entry.getValue().attempts() + 1);
        leaseUntil = now.plus(duration);
        jobs.put(entry.getKey(), new Job(activeLease.attempt(), leaseUntil, activeLease.token()));
        return Optional.of(activeLease);
    }
    @Override public synchronized boolean complete(Lease lease, CatalogEvent result, Instant now) {
        if (!lease.eventId().equals(result.id())) throw new IllegalArgumentException("Lease/event mismatch");
        if (!owns(lease, now)) return false;
        events.put(result.id(), result);
        jobs.remove(lease.eventId());
        activeLease = null;
        leaseUntil = Instant.MIN;
        return true;
    }
    @Override public synchronized boolean defer(Lease lease, Instant availableAt, Instant now) {
        if (!owns(lease, now)) return false;
        jobs.put(lease.eventId(), new Job(Math.max(0, lease.attempt() - 1), availableAt, null));
        activeLease = null;
        leaseUntil = Instant.MIN;
        return true;
    }
    @Override public synchronized boolean retry(Lease lease, Instant availableAt, String reason, Instant now) {
        if (!owns(lease, now)) return false;
        jobs.put(lease.eventId(), new Job(lease.attempt(), availableAt, null));
        activeLease = null;
        leaseUntil = Instant.MIN;
        return true;
    }
    @Override public synchronized void enqueue(String id, Instant now) {
        var event = events.get(id);
        if (event == null) throw new IllegalArgumentException("Catalog event not found");
        if (event.algorithmVersion() != CatalogEvent.CURRENT_ALGORITHM_VERSION) throw new IllegalArgumentException("Obsolete catalog algorithm; regenerate the event instead");
        if (activeLease != null && activeLease.eventId().equals(id) && leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("Validation is already running");
        }
        exhausted.remove(id);
        jobs.put(id, new Job(0, now, null));
    }
    @Override public synchronized RecoveryPage requeueExhausted(String after, int limit, Instant now) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Recovery limit must be 1–100");
        if (after != null && (!after.matches("[A-Za-z0-9_:-]{1,180}") || after.matches("__.*__")))
            throw new IllegalArgumentException("Invalid recovery cursor");
        var page = exhausted.stream().filter(id -> after == null || id.compareTo(after) > 0).sorted().limit(limit + 1L).toList();
        var selected = page.stream().limit(limit).toList();
        int queued = 0;
        for (String id : selected) {
            if (!events.containsKey(id)) { exhausted.remove(id); jobs.remove(id); continue; }
            enqueue(id, now);
            queued++;
        }
        return new RecoveryPage(queued, page.size() > limit ? selected.getLast() : null);
    }

    @Override public synchronized boolean abandon(Lease lease, String reason, Instant now) {
        if (!owns(lease, now)) return false;
        if (reason.startsWith("Retry budget exhausted")) exhausted.add(lease.eventId());
        jobs.remove(lease.eventId());
        activeLease = null;
        leaseUntil = Instant.MIN;
        return true;
    }
    private boolean owns(Lease lease, Instant now) {
        return lease.equals(activeLease) && leaseUntil.isAfter(now);
    }
    private record Job(int attempts, Instant availableAt, String token) {}

}

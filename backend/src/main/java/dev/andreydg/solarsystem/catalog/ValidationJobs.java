package dev.andreydg.solarsystem.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Durable outbox: events and initial jobs are inserted atomically by the catalog repository. */
public interface ValidationJobs {
    Optional<Lease> claim(Instant now, Duration duration);
    boolean complete(Lease lease, CatalogEvent result, Instant now);
    boolean retry(Lease lease, Instant availableAt, String reason, Instant now);
    boolean abandon(Lease lease, String reason, Instant now);
    void enqueue(String eventId, Instant now);
    RecoveryPage requeueExhausted(String after, int limit, Instant now);
    record RecoveryPage(int queued, String nextCursor) {}
    record Lease(String eventId, String token, int attempt) {}
}

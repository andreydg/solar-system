package dev.andreydg.solarsystem.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Durable outbox: events and initial jobs are inserted atomically by the catalog repository. */
public interface ValidationJobs {
    Optional<Lease> claim(Instant now, Duration duration);
    boolean complete(Lease lease, CatalogEvent result, Instant now);
    boolean complete(Lease lease, CatalogEvent result, Instant now, String privateError);
    static String privateDiagnostic(String reason) {
        return reason == null ? null : reason.substring(0, Math.min(8000, reason.length()));
    }
    int MAX_FREE_DEFERRALS = 2;
    boolean defer(Lease lease, Instant availableAt, Instant now);
    boolean retry(Lease lease, Instant availableAt, String reason, Instant now);
    boolean abandon(Lease lease, String reason, Instant now);
    void enqueue(String eventId, Instant now);
    RecoveryPage requeueExhausted(String after, int limit, Instant now);
    record RecoveryPage(int queued, String nextCursor) {}
    record Lease(String eventId, String token, int attempt, int deferrals) {
        public Lease(String eventId, String token, int attempt) { this(eventId, token, attempt, 0); }
        public int attemptsAfterDeferral() { return deferrals >= MAX_FREE_DEFERRALS ? attempt : Math.max(0, attempt - 1); }
        public int deferralsAfterDeferral() { return deferrals >= MAX_FREE_DEFERRALS ? 0 : deferrals + 1; }
    }
}

package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.storage.EventCatalogStorageException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Authenticated request-scoped worker; scientific failures never include persistence failures. */
@Service
public class ValidationWorker {
    private static final Logger log = LoggerFactory.getLogger(ValidationWorker.class);
    public static final int MAX_ATTEMPTS = 12;
    public static final Duration LEASE = Duration.ofMinutes(10);
    private final ValidationJobs jobs;
    private final EventCatalogService catalog;
    private final JplValidationService validation;
    private final JplProperties properties;
    private final Clock clock;
    @Autowired
    public ValidationWorker(ValidationJobs jobs, EventCatalogService catalog, JplValidationService validation, JplProperties properties) {
        this(jobs, catalog, validation, properties, Clock.systemUTC());
    }
    ValidationWorker(ValidationJobs jobs, EventCatalogService catalog, JplValidationService validation, JplProperties properties, Clock clock) {
        this.jobs = jobs; this.catalog = catalog; this.validation = validation; this.properties = properties; this.clock = clock;
    }
    public record DrainResult(String status, int processed) {}
    public DrainResult drainBatch() {
        try (var budget = ValidationBudget.start(Duration.ofMinutes(8))) {
            int processed = 0;
            while (processed < 200 && ValidationBudget.hasTime()) {
                String status = drainOne();
                if (status.equals("idle-or-busy") || status.equals("paused") || status.equals("lease-lost") || status.equals("budget-deferred"))
                    return new DrainResult(status, processed);
                processed++;
            }
            return new DrainResult("budget-reached", processed);
        }
    }
    public String drainOne() {
        if (!properties.asyncValidationEnabled()) return "paused";
        var claimed = jobs.claim(clock.instant(), LEASE);
        if (claimed.isEmpty()) return "idle-or-busy";
        var lease = claimed.get();
        try {
            return process(lease);
        } catch (EventCatalogStorageException unavailable) {
            // Cleanup also requires storage. A database outage falls back to the expiring lease.
            throw unavailable;
        } catch (RuntimeException unexpected) {
            log.error("Unexpected worker failure for {}", lease.eventId(), unexpected);
            try {
                // Job-only cleanup releases the global lease without changing accepted event data.
                return abandon(lease, "Unexpected worker failure: " + unexpected.getClass().getSimpleName());
            } catch (RuntimeException cleanupFailure) {
                unexpected.addSuppressed(cleanupFailure);
                throw unexpected;
            }
        }
    }
    private String process(ValidationJobs.Lease lease) {
        // Check before deserializing a possibly poisoned document.
        if (lease.attempt() > MAX_ATTEMPTS) return abandon(lease, "Retry budget exhausted");
        CatalogEvent event;
        try {
            var found = catalog.findById(lease.eventId());
            if (found.isEmpty()) return abandon(lease, "Catalog event missing");
            event = found.get();
            if (event.algorithmVersion() != CatalogEvent.CURRENT_ALGORITHM_VERSION) return abandon(lease, "Obsolete algorithm; regenerate event");
        } catch (EventCatalogStorageException failure) {
            return retry(lease, "Temporary catalog read failure");
        } catch (RuntimeException failure) {
            return abandon(lease, "Unreadable catalog event: " + failure.getClass().getSimpleName());
        }
        CatalogEvent result;
        String privateError = null;
        try {
            // Explicit requeues always revalidate, while the last accepted values remain served.
            result = validation.validateEvent(event.id());
        } catch (ValidationBudget.Exhausted budget) {
            return jobs.defer(lease, clock.instant().plusSeconds(1), clock.instant()) ? "budget-deferred" : "lease-lost";
        } catch (RuntimeException failure) {
            log.warn("Validation attempt {} failed for {}: {}", lease.attempt(), event.id(), failure.getClass().getSimpleName());
            boolean transientFailure = failure instanceof JplHorizonsException jpl && jpl.isTransient()
                || failure instanceof EventCatalogStorageException;
            if (transientFailure) return retry(lease, "Temporary validation failure");
            String reason = failure.toString();
            privateError = reason.substring(0, Math.min(8000, reason.length()));
            if (event.validationStatus() == ValidationStatus.VALIDATED) return abandon(lease, privateError);
            result = event.failed("JPL did not confirm this event. An administrator can inspect the validation job.");
        }
        // A failed write says nothing about geometry. Leave the event unchanged and reschedule.
        try {
            return (privateError == null ? jobs.complete(lease, result, clock.instant())
                : jobs.complete(lease, result, clock.instant(), privateError))
                ? (result.validationStatus() == ValidationStatus.VALIDATED ? "validated" : "failed") : "lease-lost";
        } catch (EventCatalogStorageException failure) {
            return retry(lease, "Temporary validation result storage failure");
        }
    }
    private String retry(ValidationJobs.Lease lease, String reason) {
        if (lease.attempt() >= MAX_ATTEMPTS) return abandon(lease, "Retry budget exhausted: " + reason);
        return jobs.retry(lease, clock.instant().plus(retryDelay(lease.attempt())), reason, clock.instant())
            ? "retry-scheduled" : "lease-lost";
    }
    static Duration retryDelay(int attempt) {
        return Duration.ofSeconds(Math.min(6 * 3600, 300L << Math.min(16, Math.max(0, attempt - 1))));
    }
    private String abandon(ValidationJobs.Lease lease, String reason) {
        return jobs.abandon(lease, reason, clock.instant()) ? "failed" : "lease-lost";
    }
}

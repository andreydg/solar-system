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
    static final Duration BUDGET = Duration.ofMinutes(8);
    private static final Duration TYPICAL_JOB = Duration.ofSeconds(5);
    private final Duration worstCaseJob;
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
        this.worstCaseJob = worstCaseJob(properties);
        if (worstCaseJob.compareTo(BUDGET) >= 0)
            throw new IllegalArgumentException("JPL retry configuration exceeds the 480s validation budget");
        var claimWindow = BUDGET.minus(worstCaseJob);
        (claimWindow.compareTo(TYPICAL_JOB) < 0 ? log.atWarn() : log.atInfo()).log(
            "Validation drain budget={}ms, reservation={}ms, claim window={}ms",
            BUDGET.toMillis(), worstCaseJob.toMillis(), claimWindow.toMillis());
    }
    public record DrainResult(String status, int processed) {}
    public DrainResult drainBatch() {
        try (var budget = ValidationBudget.start(BUDGET)) {
            int processed = 0;
            while (processed < 200 && ValidationBudget.hasTimeFor(worstCaseJob)) {
                String status = drainOne();
                if (status.equals("idle-or-busy") || status.equals("paused") || status.equals("lease-lost") || status.equals("budget-deferred"))
                    return new DrainResult(status, processed);
                processed++;
            }
            return new DrainResult("budget-reached", processed);
        }
    }
    public String drainOne() {
        // Preserve the batch deadline; standalone callers get the same bounded retry policy.
        try (var budget = ValidationBudget.isActive() ? null : ValidationBudget.start(BUDGET)) {
            return drainOneWithinBudget();
        }
    }
    private String drainOneWithinBudget() {
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
                return abandon(lease, "Unexpected worker failure: " + unexpected);
            } catch (RuntimeException cleanupFailure) {
                unexpected.addSuppressed(cleanupFailure);
                throw unexpected;
            }
        }
    }
    private String process(ValidationJobs.Lease lease) {
        // Check before deserializing a possibly poisoned document.
        if (lease.attempt() > MAX_ATTEMPTS) return abandon(lease, "Retry budget exhausted after budget deferrals");
        CatalogEvent event;
        try {
            var found = catalog.findById(lease.eventId());
            if (found.isEmpty()) return abandon(lease, "Catalog event missing");
            event = found.get();
            if (event.algorithmVersion() != CatalogEvent.CURRENT_ALGORITHM_VERSION) return abandon(lease, "Obsolete algorithm; regenerate event");
        } catch (EventCatalogStorageException failure) {
            return retry(lease, "Temporary catalog read failure: " + failure);
        } catch (RuntimeException failure) {
            return abandon(lease, "Unreadable catalog event: " + failure);
        }
        CatalogEvent result;
        String privateError = null;
        try {
            // Explicit requeues always revalidate, while the last accepted values remain served.
            result = validation.validateEvent(event.id());
        } catch (ValidationBudget.Exhausted budget) {
            return jobs.defer(lease, clock.instant().plusSeconds(1), clock.instant()) ? "budget-deferred" : "lease-lost";
        } catch (RuntimeException failure) {
            log.warn("Validation attempt {} failed for {}: {}", lease.attempt(), event.id(), failure.toString());
            boolean transientFailure = failure instanceof JplHorizonsException jpl && jpl.isTransient()
                || failure instanceof EventCatalogStorageException;
            if (transientFailure) return retry(lease, failure.toString(), event);
            String reason = failure.toString();
            privateError = ValidationJobs.privateDiagnostic(reason);
            if (event.validationStatus() == ValidationStatus.VALIDATED) return abandon(lease, privateError);
            result = failure instanceof JplCoverageException ? event.unavailable(coverageSummary(failure))
                : event.failed("JPL did not confirm this event. An administrator can inspect the validation job.");
        }
        // A failed write says nothing about geometry. Leave the event unchanged and reschedule.
        try {
            return (privateError == null ? jobs.complete(lease, result, clock.instant())
                : jobs.complete(lease, result, clock.instant(), privateError))
                ? result.validationStatus().storedValue() : "lease-lost";
        } catch (EventCatalogStorageException failure) {
            return retry(lease, "Temporary validation result storage failure: " + failure);
        }
    }
    private static String coverageSummary(RuntimeException failure) {
        var boundary = java.util.regex.Pattern.compile("(?i)\\b(prior to|before|after)\\s+(?:A\\.D\\.\\s+)?(\\d{4}-(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)-\\d{2})\\b")
            .matcher(java.util.Objects.toString(failure.getMessage(), ""));
        return boundary.find() ? "JPL ephemeris unavailable " + boundary.group(1).toLowerCase(java.util.Locale.ROOT)
            + " " + boundary.group(2) + "." : "JPL ephemeris is unavailable for this target and date.";
    }
    private String retry(ValidationJobs.Lease lease, String reason, CatalogEvent event) {
        if (lease.attempt() >= MAX_ATTEMPTS && event.validationStatus() == ValidationStatus.PENDING) {
            return jobs.complete(lease, event.unavailable("Retry budget exhausted: JPL is temporarily unavailable. An administrator can retry this event."),
                clock.instant(), ValidationJobs.privateDiagnostic("Retry budget exhausted: " + reason)) ? "failed" : "lease-lost";
        }
        return retry(lease, reason);
    }
    private String retry(ValidationJobs.Lease lease, String reason) {
        if (lease.attempt() >= MAX_ATTEMPTS) return abandon(lease, "Retry budget exhausted: " + reason);
        return jobs.retry(lease, clock.instant().plus(retryDelay(lease.attempt())), ValidationJobs.privateDiagnostic(reason), clock.instant())
            ? "retry-scheduled" : "lease-lost";
    }
    static Duration worstCaseJob(JplProperties properties) {
        int attempts = Math.max(1, properties.maxRetries());
        if (attempts > 20 || properties.retryBackoffMillis() < 0)
            throw new IllegalArgumentException("Invalid JPL retry configuration");
        try {
            long pauses = Math.multiplyExact(properties.retryBackoffMillis(), (1L << (attempts - 1)) - 1);
            // Four sequential Horizons requests (two bodies, coarse + refined); each
            // includes semaphore wait (5s), connection (5s), and response (15s).
            long attemptMillis = JplHorizonsClient.CAPACITY_WAIT
                .plus(dev.andreydg.solarsystem.config.HttpClientConfig.CONNECT_TIMEOUT)
                .plus(dev.andreydg.solarsystem.config.HttpClientConfig.READ_TIMEOUT).toMillis();
            long requests = Math.multiplyExact(attempts, attemptMillis);
            return Duration.ofMillis(Math.addExact(10_000, Math.multiplyExact(JplValidationService.MAX_REQUESTS_PER_VALIDATION, Math.addExact(requests, pauses))));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("JPL retry configuration exceeds the validation budget", overflow);
        }
    }
    static Duration retryDelay(int attempt) {
        return Duration.ofSeconds(Math.min(6 * 3600, 300L << Math.min(16, Math.max(0, attempt - 1))));
    }
    private String abandon(ValidationJobs.Lease lease, String reason) {
        return jobs.abandon(lease, ValidationJobs.privateDiagnostic(reason), clock.instant()) ? "failed" : "lease-lost";
    }
}

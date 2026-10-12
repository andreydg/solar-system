package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.JplProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Invoked during an authenticated HTTP request, not an unreliable post-response thread. */
@Service
public class ValidationWorker {
    private static final Logger log = LoggerFactory.getLogger(ValidationWorker.class);
    public static final int MAX_ATTEMPTS = 5;
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
    public String drainOne() {
        if (!properties.asyncValidationEnabled()) return "paused";
        var claimed = jobs.claim(clock.instant(), LEASE);
        if (claimed.isEmpty()) return "idle-or-busy";
        var lease = claimed.get();
        var found = catalog.findById(lease.eventId());
        if (found.isEmpty()) return jobs.abandon(lease, "Catalog event missing", clock.instant()) ? "failed" : "lease-lost";
        var event = found.get();
        try {
            if (lease.attempt() > MAX_ATTEMPTS) {
                return jobs.complete(lease, event.failed("Validation retry budget exhausted"), clock.instant()) ? "failed" : "lease-lost";
            }
            var result = event.validationStatus() == ValidationStatus.VALIDATED ? event : validation.validateEvent(event.id());
            return jobs.complete(lease, result, clock.instant()) ? "validated" : "lease-lost";
        } catch (RuntimeException failure) {
            log.warn("Validation attempt {} failed for {}: {}", lease.attempt(), event.id(), failure.toString());
            boolean permanent = failure instanceof JplHorizonsException jpl && !jpl.isTransient();
            if (permanent || lease.attempt() >= MAX_ATTEMPTS) {
                return jobs.complete(lease, event.failed(permanent ? "JPL rejected event validation" : "Validation retry budget exhausted"), clock.instant())
                    ? "failed" : "lease-lost";
            }
            var delay = Duration.ofSeconds(60L << (lease.attempt() - 1));
            return jobs.retry(lease, clock.instant().plus(delay), "Temporary validation failure", clock.instant()) ? "retry-scheduled" : "lease-lost";
        }
    }
}

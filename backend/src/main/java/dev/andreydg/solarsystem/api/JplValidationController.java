package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.jpl.JplValidationService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JplValidationController {
    private final JplValidationService validationService;
    private final dev.andreydg.solarsystem.catalog.ValidationJobs jobs;
    private final dev.andreydg.solarsystem.jpl.ValidationWorker worker;

    public JplValidationController(JplValidationService validationService, dev.andreydg.solarsystem.catalog.ValidationJobs jobs,
        dev.andreydg.solarsystem.jpl.ValidationWorker worker) {
        this.validationService = validationService;
        this.jobs = jobs;
        this.worker = worker;
    }

    @GetMapping("/api/validation/jpl/position")
    public JplVectorDto position(@RequestParam String body, @RequestParam String time) {
        dev.andreydg.solarsystem.catalog.CatalogLimits.checkTime(Instant.parse(time));
        return JplVectorDto.from(validationService.position(
            BodyId.fromApiValue(body),
            Instant.parse(time)
        ));
    }

    @PostMapping("/api/validation/jpl/event/{eventId}")
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> validateEvent(@PathVariable String eventId) {
        jobs.enqueue(eventId, Instant.now());
        return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("status", "queued", "eventId", eventId));
    }

    @PostMapping("/api/admin/validation/drain")
    public dev.andreydg.solarsystem.jpl.ValidationWorker.DrainResult drain() {
        return worker.drainBatch();
    }

    @PostMapping("/api/admin/validation/requeue-exhausted")
    public dev.andreydg.solarsystem.catalog.ValidationJobs.RecoveryPage requeueExhausted(
        @RequestParam(required = false) String after, @RequestParam(defaultValue = "100") int limit) {
        return jobs.requeueExhausted(after, limit, Instant.now());
    }
}

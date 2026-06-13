package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.jpl.JplValidationService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JplValidationController {
    private final JplValidationService validationService;

    public JplValidationController(JplValidationService validationService) {
        this.validationService = validationService;
    }

    @GetMapping("/api/validation/jpl/position")
    public JplVectorDto position(@RequestParam String body, @RequestParam String time) {
        return JplVectorDto.from(validationService.position(
            BodyId.fromApiValue(body),
            Instant.parse(time)
        ));
    }

    @GetMapping("/api/validation/jpl/event/{eventId}")
    public EventDto validateEvent(@PathVariable String eventId) {
        return EventDto.from(validationService.validateEvent(eventId));
    }
}

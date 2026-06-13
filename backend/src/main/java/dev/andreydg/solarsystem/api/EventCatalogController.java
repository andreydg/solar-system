package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.EventCatalogService;
import dev.andreydg.solarsystem.catalog.EventType;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventCatalogController {
    private final EventCatalogService service;

    public EventCatalogController(EventCatalogService service) {
        this.service = service;
    }

    @GetMapping("/api/events")
    public List<EventDto> queryEvents(
        @RequestParam String type,
        @RequestParam String bodyA,
        @RequestParam String bodyB,
        @RequestParam String from,
        @RequestParam String to
    ) {
        return service.query(
                EventType.fromApiValue(type),
                BodyId.fromApiValue(bodyA),
                BodyId.fromApiValue(bodyB),
                Instant.parse(from),
                Instant.parse(to)
            )
            .stream()
            .map(EventDto::from)
            .toList();
    }

    @PostMapping("/api/events/generate")
    public List<EventDto> generateEvents(@Valid @RequestBody GenerateEventsRequest request) {
        return service.generate(
                EventType.fromApiValue(request.type()),
                BodyId.fromApiValue(request.bodyA()),
                BodyId.fromApiValue(request.bodyB()),
                Instant.parse(request.from()),
                Instant.parse(request.to())
            )
            .stream()
            .map(EventDto::from)
            .toList();
    }

    @GetMapping("/api/events/validated")
    public List<EventDto> listValidatedEvents() {
        return service.listValidatedEvents()
            .stream()
            .map(EventDto::from)
            .toList();
    }
}

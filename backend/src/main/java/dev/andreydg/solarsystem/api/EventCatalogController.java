package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.EventCatalogService;
import dev.andreydg.solarsystem.catalog.EventType;
import dev.andreydg.solarsystem.catalog.CatalogQuery;
import dev.andreydg.solarsystem.catalog.CatalogPage;
import dev.andreydg.solarsystem.catalog.CatalogLimits;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    public ResponseEntity<List<EventDto>> queryEvents(
        @RequestParam String type,
        @RequestParam String bodyA,
        @RequestParam String bodyB,
        @RequestParam String from,
        @RequestParam String to,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "false") boolean descending,
        @RequestParam(required = false) String cursor
    ) {
        CatalogLimits.checkRange(Instant.parse(from), Instant.parse(to));
        var eventType = EventType.fromApiValue(type);
        var a = BodyId.fromApiValue(bodyA);
        var b = BodyId.fromApiValue(bodyB);
        if (!dev.andreydg.solarsystem.catalog.EventPairing.isValid(eventType, a, b)) throw new IllegalArgumentException("Invalid event pair");
        return page(service.search(new CatalogQuery(eventType, a, b, Instant.parse(from), Instant.parse(to),
            false, descending, publicLimit(limit), cursor)));
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
    public ResponseEntity<List<EventDto>> listValidatedEvents(
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String bodyA,
        @RequestParam(required = false) String bodyB,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "false") boolean descending,
        @RequestParam(required = false) String cursor
    ) {
        return page(service.search(new CatalogQuery(
            Optional.ofNullable(type).filter(value -> !value.isBlank()).map(EventType::fromApiValue).orElse(null),
            Optional.ofNullable(bodyA).filter(value -> !value.isBlank()).map(BodyId::fromApiValue).orElse(null),
            Optional.ofNullable(bodyB).filter(value -> !value.isBlank()).map(BodyId::fromApiValue).orElse(null),
            from == null ? CatalogLimits.MIN_TIME : Instant.parse(from),
            to == null ? CatalogLimits.MAX_TIME.minusNanos(1) : Instant.parse(to),
            true, descending, publicLimit(limit), cursor)));
    }

    private static int publicLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Page size must be 1–100");
        return limit;
    }

    private static ResponseEntity<List<EventDto>> page(CatalogPage page) {
        var response = ResponseEntity.ok();
        if (page.nextCursor() != null) response.header("X-Next-Cursor", page.nextCursor());
        return response.body(page.events().stream().map(EventDto::from).toList());
    }
}

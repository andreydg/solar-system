package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.EventType;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogOptionsController {

    @GetMapping("/api/catalog/options")
    public CatalogOptionsDto options() {
        return new CatalogOptionsDto(
            Arrays.stream(EventType.values())
                .map(EventType::apiValue)
                .toList(),
            Arrays.stream(BodyId.values())
                .map(BodyId::apiValue)
                .toList(),
            List.of(
                "Closest and farthest approaches support any two catalog bodies.",
                "Earth-observer events require Earth and one target body.",
                "Greatest elongation and transit require Mercury or Venus.",
                "Perihelion requires Earth and a small body (Ceres, Vesta, Encke, or Halley).",
                "Small-body positions are computed with JPL Horizons."
            )
        );
    }

    public record CatalogOptionsDto(
        List<String> eventTypes,
        List<String> bodies,
        List<String> notes
    ) {
    }
}

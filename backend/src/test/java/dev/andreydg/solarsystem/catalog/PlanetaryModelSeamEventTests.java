package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import dev.andreydg.solarsystem.ephemeris.RecordedOrbitFixtures;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanetaryModelSeamEventTests {
    private final dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider provider = RecordedOrbitFixtures.provider();
    private final EventCatalogService service = new EventCatalogService(provider, new InMemoryEventCatalogRepository());

    @Test void formerTableSwitchesDoNotGenerateFalsePairExtremaOrNeptuneRetrograde() {
        Object[][] cases = {
            {"2050-01-01T00:00:00Z", BodyId.URANUS, BodyId.NEPTUNE},
            {"2050-01-01T00:00:00Z", BodyId.SATURN, BodyId.VESTA},
            {"2050-01-01T00:00:00Z", BodyId.SATURN, BodyId.HALLEY},
            {"2050-01-01T00:00:00Z", BodyId.URANUS, BodyId.HALLEY},
            {"1799-12-30T12:00:00Z", BodyId.SATURN, BodyId.URANUS},
            {"1799-12-30T12:00:00Z", BodyId.MARS, BodyId.NEPTUNE},
            {"1799-12-30T12:00:00Z", BodyId.SATURN, BodyId.HALLEY},
            {"1799-12-30T12:00:00Z", BodyId.NEPTUNE, BodyId.HALLEY},
            {"1799-12-30T12:00:00Z", BodyId.MARS, BodyId.ENCKE}
        };
        for (Object[] c : cases) for (var type : List.of(EventType.CLOSEST_APPROACH, EventType.FARTHEST_APPROACH)) {
            Instant seam = Instant.parse((String)c[0]);
            var events = service.generate(type, (BodyId)c[1], (BodyId)c[2], seam.minusSeconds(86400), seam.plusSeconds(86400));
            if (c[1] == BodyId.MARS && c[2] == BodyId.ENCKE && type == EventType.FARTHEST_APPROACH) {
                // The repeated-orbit model has a genuine local maximum 16h later; do not
                // suppress that provisional candidate to make a blanket "no events" test pass.
                assertThat(events).singleElement().satisfies(event -> {
                    assertThat(event.computedTimeUtc()).isEqualTo(Instant.parse("1799-12-31T04:00:00Z"));
                    for (int hours : new int[] {-6, 6}) {
                        Instant nearby = event.computedTimeUtc().plusSeconds(hours * 3600);
                        assertThat(event.computedDistanceAu()).isGreaterThan(provider.heliocentricPosition(BodyId.MARS, nearby)
                            .distanceTo(provider.heliocentricPosition(BodyId.ENCKE, nearby)));
                    }
                });
            } else assertThat(events).as("%s %s/%s at %s", type, c[1], c[2], seam).isEmpty();
        }
        Instant seam = Instant.parse("1799-12-30T12:00:00Z");
        for (var type : List.of(EventType.STATIONARY, EventType.RETROGRADE_START, EventType.RETROGRADE_END))
            assertThat(service.generate(type, BodyId.EARTH, BodyId.NEPTUNE, seam.minusSeconds(2 * 86400), seam.plusSeconds(2 * 86400)))
                .as(type.name()).isEmpty();
        for (var type : List.of(EventType.CLOSEST_APPROACH, EventType.FARTHEST_APPROACH))
            assertThat(service.generate(type, BodyId.URANUS, BodyId.NEPTUNE,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2055-01-01T00:00:00Z"))).isEmpty();
    }

    @Test void realNearbyEventsRemainAndMarsNeptuneIsNotDisplacedToTheSwitch() {
        // Independent astronomy-engine reference dates supplied by the sixth-round audit.
        Object[][] cases = {
            {EventType.CLOSEST_APPROACH, BodyId.MERCURY, BodyId.SATURN, "1800-01-02T07:00:00Z"},
            {EventType.GREATEST_ELONGATION, BodyId.EARTH, BodyId.VENUS, "1799-12-28T00:00:00Z"},
            {EventType.STATIONARY, BodyId.EARTH, BodyId.NEPTUNE, "1800-02-17T00:00:00Z"},
            {EventType.CLOSEST_APPROACH, BodyId.MERCURY, BodyId.JUPITER, "2049-12-31T22:00:00Z"},
            {EventType.CLOSEST_APPROACH, BodyId.MARS, BodyId.NEPTUNE, "1799-12-27T02:00:00Z"}
        };
        for (Object[] c : cases) {
            Instant reference = Instant.parse((String)c[3]);
            assertThat(service.generate((EventType)c[0], (BodyId)c[1], (BodyId)c[2],
                reference.minus(Duration.ofDays(7)), reference.plus(Duration.ofDays(7))))
                .as(java.util.Arrays.toString(c)).singleElement().satisfies(event ->
                    assertThat(Duration.between(reference, event.computedTimeUtc()).abs()).isLessThan(Duration.ofDays(1)));
        }
    }

    @Test void repeatedSmallBodyOrbitSeamsStillRequireTheDocumentedProvisionalCaveat() {
        // This intentionally pins the documented limitation, not a physical-event acceptance claim.
        var events = service.generate(EventType.STATIONARY, BodyId.EARTH, BodyId.VESTA,
            Instant.parse("2016-12-01T00:00:00Z"), Instant.parse("2016-12-10T00:00:00Z"));
        assertThat(events).hasSize(2);
        assertThat(Duration.between(events.get(0).computedTimeUtc(), events.get(1).computedTimeUtc()).abs())
            .isLessThanOrEqualTo(Duration.ofDays(1));
        assertThat(events).allSatisfy(event -> assertThat(event.validationStatus()).isEqualTo(ValidationStatus.PENDING));
    }
}

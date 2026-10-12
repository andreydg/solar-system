package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class CatalogLimitsTests {
    @Test void rejectsReversedAndUnboundedRangesBeforeDoingWork() {
        var ephemeris = mock(CompositeEphemerisProvider.class);
        var service = new EventCatalogService(ephemeris, new InMemoryEventCatalogRepository(), mock(ApplicationEventPublisher.class));
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        for (Instant to : new Instant[] {from, from.minusSeconds(1), from.plusSeconds(366L * 86400 * 32)}) {
            assertThatThrownBy(() -> service.generate(EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS, from, to))
                .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(ephemeris);
    }
    @Test void rejectsDatesOutsideSupportedBounds() {
        assertThatThrownBy(() -> CatalogLimits.checkTime(Instant.MIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogLimits.checkTime(Instant.MAX)).isInstanceOf(IllegalArgumentException.class);
    }
}

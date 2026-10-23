package dev.andreydg.solarsystem.jpl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class JplAcceptanceTests {
    private static final Instant T = Instant.parse("2027-02-19T00:00:00Z");
    private final JplHorizonsClient horizons = mock(JplHorizonsClient.class);
    private final EventCatalogService catalog = mock(EventCatalogService.class);
    private final JplValidationService validation = new JplValidationService(catalog, horizons);

    @Test void ninetyDegreeMismatchIsNotValidated() {
        synthetic(time -> new Vector3Au(1, 1, 0));
        when(catalog.findById("event")).thenReturn(Optional.of(event(EventType.OPPOSITION, BodyId.MARS)));
        assertThatThrownBy(() -> validation.validateEvent("event")).isInstanceOf(JplHorizonsException.class);
    }
    @Test void transitRequiresDiskOverlapAndThePlanetInFrontOfTheSun() {
        when(catalog.findById("event")).thenReturn(Optional.of(event(EventType.TRANSIT, BodyId.MERCURY)));
        synthetic(time -> new Vector3Au(0.5, hours(time) * 0.001, 0.01));
        assertThatThrownBy(() -> validation.validateEvent("event")).isInstanceOf(JplHorizonsException.class).hasMessageContaining("solar disk");
        synthetic(time -> new Vector3Au(-0.5, hours(time) * 0.001, 0.00001));
        assertThatThrownBy(() -> validation.validateEvent("event")).isInstanceOf(JplHorizonsException.class).hasMessageContaining("solar disk");
        synthetic(time -> new Vector3Au(0.5, hours(time) * 0.001, 0.00001));
        assertThat(validation.validateEvent("event").validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
    }
    @Test void monotonicDistanceCannotBecomeValidatedAtTheWindowBoundary() {
        when(catalog.findById("event")).thenReturn(Optional.of(event(EventType.CLOSEST_APPROACH, BodyId.MARS)));
        synthetic(time -> new Vector3Au(2 + hours(time) * 0.001, 0, 0));
        assertThatThrownBy(() -> validation.validateEvent("event")).isInstanceOf(JplHorizonsException.class);
    }
    private void synthetic(Function<Instant, Vector3Au> target) {
        doAnswer(invocation -> {
            BodyId body = invocation.getArgument(0); Instant from = invocation.getArgument(1); Instant to = invocation.getArgument(2);
            String step = invocation.getArgument(3); long seconds = Long.parseLong(step.replace("m", "")) * 60;
            var samples = new ArrayList<JplVector>();
            for (Instant time = from; !time.isAfter(to); time = time.plusSeconds(seconds)) {
                samples.add(new JplVector(body, time, body == BodyId.EARTH ? new Vector3Au(1,0,0) : target.apply(time), "", ""));
            }
            return samples;
        }).when(horizons).vectors(any(), any(), any(), any());
    }
    private static double hours(Instant time) { return Duration.between(T, time).toSeconds() / 3600.0; }
    private CatalogEvent event(EventType type, BodyId target) {
        return new CatalogEvent("event", type, BodyId.EARTH, target, T, 0.6, 180.0, null, "TEST",
            null, null, null, null, null, ValidationStatus.PENDING, T.minusSeconds(86400), T.plusSeconds(86400), T, null, null, null);
    }
}

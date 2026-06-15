package dev.andreydg.solarsystem.jpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.catalog.CatalogEvent;
import dev.andreydg.solarsystem.catalog.EventType;
import dev.andreydg.solarsystem.catalog.ValidationStatus;
import dev.andreydg.solarsystem.catalog.EventCatalogService;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JplValidationServiceTests {

    private final JplHorizonsClient horizonsClient = mock(JplHorizonsClient.class);
    private final EventCatalogService catalogService = mock(EventCatalogService.class);
    private final JplValidationService service =
        new JplValidationService(catalogService, horizonsClient, new JplProperties("http://example", false));

    private static final Instant EVENT_TIME = Instant.parse("2027-02-19T00:00:00Z");

    @Test
    void validatesDistanceEventWithBatchedRangeRequests() {
        CatalogEvent event = distanceEvent(EventType.CLOSEST_APPROACH, 0.6);
        when(catalogService.findById("evt")).thenReturn(Optional.of(event));
        when(catalogService.storeValidation(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Earth fixed at origin-ish; Mars sweeps closer then farther so the minimum is well-defined.
        stubSweep(BodyId.EARTH, t -> new Vector3Au(1.0, 0.0, 0.0));
        stubSweep(BodyId.MARS, JplValidationServiceTests::marsClosestMidWindow);

        service.validateEvent("evt");

        // Each body is queried via the batch range API (vectors), never the per-point vector().
        verify(horizonsClient, never()).vector(any(), any());
        verify(horizonsClient, org.mockito.Mockito.atLeastOnce())
            .vectors(eq(BodyId.EARTH), any(), any(), any());
        verify(horizonsClient, org.mockito.Mockito.atLeastOnce())
            .vectors(eq(BodyId.MARS), any(), any(), any());

        ArgumentCaptor<Instant> validatedTime = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Double> validatedDistance = ArgumentCaptor.forClass(Double.class);
        verify(catalogService).storeValidation(
            eq(event), validatedTime.capture(), validatedDistance.capture(), any(), any(), any(), any());

        // The minimum separation in the stubbed sweep is at the window center, distance ~0.5 AU.
        assertThat(validatedDistance.getValue()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void usesMinuteBasedHorizonsStep() {
        CatalogEvent event = distanceEvent(EventType.CLOSEST_APPROACH, 0.6);
        when(catalogService.findById("evt")).thenReturn(Optional.of(event));
        lenient().when(catalogService.storeValidation(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        stubSweep(BodyId.EARTH, t -> new Vector3Au(1.0, 0.0, 0.0));
        stubSweep(BodyId.MARS, JplValidationServiceTests::marsClosestMidWindow);

        service.validateEvent("evt");

        ArgumentCaptor<String> step = ArgumentCaptor.forClass(String.class);
        verify(horizonsClient, org.mockito.Mockito.atLeastOnce())
            .vectors(eq(BodyId.EARTH), any(), any(), step.capture());
        // 6h coarse / 1h refined steps must be expressed in minutes, matching the working "Nm" form.
        assertThat(step.getAllValues()).allSatisfy(value -> assertThat(value).matches("\\d+m"));
        assertThat(step.getAllValues()).contains("360m", "60m");
    }

    private void stubSweep(BodyId body, java.util.function.Function<Instant, Vector3Au> positionAt) {
        lenient().when(horizonsClient.vectors(eq(body), any(), any(), any())).thenAnswer(invocation -> {
            Instant from = invocation.getArgument(1);
            Instant to = invocation.getArgument(2);
            String step = invocation.getArgument(3);
            long minutes = Long.parseLong(step.replace("m", ""));
            Duration stepDuration = Duration.ofMinutes(minutes);
            List<JplVector> samples = new ArrayList<>();
            for (Instant t = from; !t.isAfter(to); t = t.plus(stepDuration)) {
                samples.add(new JplVector(body, t, positionAt.apply(t), t.toString(), "row"));
            }
            return samples;
        });
    }

    // Mars sits 0.5 AU from Earth at the window center (the event time) and farther toward the edges.
    private static Vector3Au marsClosestMidWindow(Instant time) {
        double hoursFromEvent = Duration.between(EVENT_TIME, time).toMinutes() / 60.0;
        double offset = Math.abs(hoursFromEvent) * 0.01;
        return new Vector3Au(1.5 + offset, 0.0, 0.0);
    }

    private static CatalogEvent distanceEvent(EventType type, double computedDistanceAu) {
        return new CatalogEvent(
            "evt", type, BodyId.EARTH, BodyId.MARS,
            EVENT_TIME, computedDistanceAu, null, null, "VSOP87A_APPROX",
            null, null, null, null, null,
            ValidationStatus.PENDING,
            EVENT_TIME.minus(Duration.ofDays(365)), EVENT_TIME.plus(Duration.ofDays(365)), EVENT_TIME,
            null, null, null
        );
    }
}

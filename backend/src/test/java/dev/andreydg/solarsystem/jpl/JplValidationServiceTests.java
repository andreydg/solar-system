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
        new JplValidationService(catalogService, horizonsClient, jplProperties(false));

    private static JplProperties jplProperties(boolean asyncEnabled) {
        return new JplProperties("http://example", asyncEnabled, 2, 3, 1);
    }

    private static final Instant EVENT_TIME = Instant.parse("2027-02-19T00:00:00Z");

    @Test
    void validatesDistanceEventWithBatchedRangeRequests() {
        CatalogEvent event = distanceEvent(EventType.CLOSEST_APPROACH, 0.6);
        when(catalogService.findById("evt")).thenReturn(Optional.of(event));

        // Earth fixed at origin-ish; Mars sweeps closer then farther so the minimum is well-defined.
        stubSweep(BodyId.EARTH, t -> new Vector3Au(1.0, 0.0, 0.0));
        stubSweep(BodyId.MARS, JplValidationServiceTests::marsClosestMidWindow);

        var result = service.validateEvent("evt");

        // Each body is queried via the batch range API (vectors), never the per-point vector().
        verify(horizonsClient, never()).vector(any(), any());
        verify(horizonsClient, org.mockito.Mockito.times(2))
            .vectors(eq(BodyId.EARTH), any(), any(), any());
        verify(horizonsClient, org.mockito.Mockito.atLeastOnce())
            .vectors(eq(BodyId.MARS), any(), any(), any());

        assertThat(org.mockito.Mockito.mockingDetails(horizonsClient).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("vectors")).count())
            .isEqualTo(JplValidationService.MAX_REQUESTS_PER_VALIDATION);
        assertThat(result.validationStatus()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(result.validatedDistanceAu()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void usesMinuteBasedHorizonsStep() {
        CatalogEvent event = distanceEvent(EventType.CLOSEST_APPROACH, 0.6);
        when(catalogService.findById("evt")).thenReturn(Optional.of(event));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(EventType.class)
    void everyEventTypePinsBothVectorApisAndItsActualRequestCount(EventType type) {
        var model = new dev.andreydg.solarsystem.ephemeris.Vsop87aEphemerisProvider();
        var repository = new dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository();
        var catalog = new EventCatalogService(new dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider(model, null, null), repository);
        CatalogEvent event;
        if (type == EventType.PERIHELION || type == EventType.TRANSIT) {
            boolean perihelion = type == EventType.PERIHELION;
            Instant time = perihelion ? EVENT_TIME : Instant.parse("2032-11-13T08:50:00Z");
            event = new CatalogEvent(type.apiValue(), type, BodyId.EARTH, perihelion ? BodyId.CERES : BodyId.MERCURY,
                time, perihelion ? 0.6 : null, perihelion ? null : 0.0, null,
                "SYNTHETIC", null, null, null, null, null, ValidationStatus.PENDING,
                time.minus(Duration.ofDays(5)), time.plus(Duration.ofDays(5)), time, null, null, null);
            repository.upsert(event);
        } else {
            BodyId target = switch (type) {
                case GREATEST_ELONGATION, BRIGHTEST_APPROACH -> BodyId.VENUS;
                default -> BodyId.MARS;
            };
            Instant from = Instant.parse("2026-01-01T00:00:00Z");
            event = catalog.generate(type, BodyId.EARTH, target, from, from.plus(Duration.ofDays(365 * 4))).getFirst();
        }
        var client = mock(JplHorizonsClient.class);
        java.util.function.BiFunction<BodyId, Instant, Vector3Au> position = (body, time) -> {
            if (body != BodyId.CERES) return model.heliocentricPosition(body, time);
            double hours = Duration.between(EVENT_TIME, time).toSeconds() / 3600.0;
            return new Vector3Au(0.6 + 0.001 * hours * hours, 0, 0);
        };
        lenient().when(client.vector(any(), any())).thenAnswer(call -> {
            BodyId body = call.getArgument(0); Instant time = call.getArgument(1);
            return new JplVector(body, time, position.apply(body, time), time.toString(), "synthetic model");
        });
        lenient().when(client.vectors(any(), any(), any(), any())).thenAnswer(call -> {
            BodyId body = call.getArgument(0); Instant from = call.getArgument(1); Instant to = call.getArgument(2);
            Duration step = Duration.ofMinutes(Long.parseLong(call.<String>getArgument(3).replace("m", "")));
            var rows = new ArrayList<JplVector>();
            for (Instant time = from; !time.isAfter(to); time = time.plus(step))
                rows.add(new JplVector(body, time, position.apply(body, time), time.toString(), "synthetic model"));
            return rows;
        });
        assertThat(new JplValidationService(catalog, client, jplProperties(false)).validateEvent(event.id()).validationStatus())
            .isEqualTo(ValidationStatus.VALIDATED);
        long calls = org.mockito.Mockito.mockingDetails(client).getInvocations().stream()
            .filter(call -> java.util.Set.of("vector", "vectors").contains(call.getMethod().getName())).count();
        int expected = switch (type) {
            case PERIHELION, OPPOSITION, CONJUNCTION -> 2;
            default -> 4;
        };
        assertThat(calls).as(type.apiValue() + " real validation request count").isEqualTo(expected);
        assertThat(calls).isLessThanOrEqualTo(JplValidationService.MAX_REQUESTS_PER_VALIDATION);
        // The distance test above pins the actual maximum to the reservation, not only an upper bound.
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

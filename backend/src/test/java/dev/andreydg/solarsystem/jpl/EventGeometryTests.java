package dev.andreydg.solarsystem.jpl;

import static org.assertj.core.api.Assertions.*;
import dev.andreydg.solarsystem.catalog.EventType;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EventGeometryTests {
    private static final Instant T = Instant.parse("2027-02-19T00:00:00Z");
    private List<EventGeometry.Sample> samples(double... values) {
        return IntStream.range(0, values.length).mapToObj(i -> new EventGeometry.Sample(T.plusSeconds((i - 2L) * 300), values[i])).toList();
    }
    @Test void rejectsNinetyDegreeMismatchAndAntipodalAliasing() {
        for (var values : List.of(samples(90, 90, 90, 90, 90), samples(-0.1, -0.05, 0, 0.05, 0.1))) {
            assertThatThrownBy(() -> EventGeometry.locate(EventType.OPPOSITION, values, T, true)).isInstanceOf(JplHorizonsException.class);
        }
        assertThat(EventGeometry.locate(EventType.OPPOSITION, samples(179.98, 179.99, 180, -179.99, -179.98), T, true).time()).isEqualTo(T);
    }
    @Test void rejectsMonotonicOrFlatDistanceSamplesAndAcceptsOnlyInteriorExtrema() {
        for (var values : List.of(samples(1,2,3,4,5), samples(5,4,3,2,1), samples(1,1,1,1,1))) {
            assertThatThrownBy(() -> EventGeometry.locate(EventType.CLOSEST_APPROACH, values, T, true)).isInstanceOf(JplHorizonsException.class);
        }
        assertThat(EventGeometry.locate(EventType.CLOSEST_APPROACH, samples(3,2,1,2,3), T, true).time()).isEqualTo(T);
        assertThat(EventGeometry.locate(EventType.FARTHEST_APPROACH, samples(1,2,3,2,1), T, true).time()).isEqualTo(T);
    }
    @Test void retrogradeMustCrossZeroInTheRequestedDirection() {
        var start = samples(-0.00004, -0.00001, 0, -0.00001, -0.00004);
        assertThat(EventGeometry.locate(EventType.RETROGRADE_START, start, T, true).time()).isEqualTo(T);
        assertThatThrownBy(() -> EventGeometry.locate(EventType.RETROGRADE_END, start, T, true)).isInstanceOf(JplHorizonsException.class);
        assertThatThrownBy(() -> EventGeometry.locate(EventType.STATIONARY, samples(0,1,2,3,4), T, true)).isInstanceOf(JplHorizonsException.class);
    }
    @Test void takesWindowGlobalExtremumRatherThanTheNearestLocalWobble() {
        var values = samples(2, 5, 2, 9, 2);
        Instant nearLesserPeak = values.get(1).time();
        assertThat(EventGeometry.locate(EventType.FARTHEST_APPROACH, values, nearLesserPeak, false))
            .isEqualTo(values.get(3));
        var minima = samples(9, 4, 9, 1, 9);
        assertThat(EventGeometry.locate(EventType.CLOSEST_APPROACH, minima, nearLesserPeak, true))
            .isEqualTo(minima.get(3));
    }
    @Test void rejectsInvalidSamples() {
        assertThatThrownBy(() -> EventGeometry.locate(EventType.TRANSIT, samples(1, Double.NaN, 1), T, true)).isInstanceOf(JplHorizonsException.class);
        assertThatThrownBy(() -> EventGeometry.locate(EventType.TRANSIT, List.of(new EventGeometry.Sample(T, 1)), T, true)).isInstanceOf(JplHorizonsException.class);
    }
}

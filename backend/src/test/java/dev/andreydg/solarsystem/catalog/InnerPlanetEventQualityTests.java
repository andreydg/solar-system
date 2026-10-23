package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.*;
import dev.andreydg.solarsystem.ephemeris.*;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InnerPlanetEventQualityTests {
    private final CompositeEphemerisProvider model =
        new CompositeEphemerisProvider(new KeplerianEphemerisProvider(), null, null);
    private final EventCatalogService photometry = service();
    private EventCatalogService service() { return new EventCatalogService(model, new InMemoryEventCatalogRepository()); }

    @Test void nextVenusGreatestBrilliancySkipsTheFaintSuperiorConjunctionWiggle() {
        var events = service().generate(EventType.BRIGHTEST_APPROACH, BodyId.EARTH, BodyId.VENUS,
            Instant.parse("2026-11-26T00:00:00Z"), Instant.parse("2029-01-01T00:00:00Z"));
        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().computedTimeUtc().atOffset(ZoneOffset.UTC).toLocalDate())
            .isEqualTo(LocalDate.parse("2028-04-29"));
    }

    @Test void mercuryBrightestListHasNoSubThresholdDipsAcrossTheSupportedEpoch() {
        int count = 0;
        for (int year = 1600; year < 3000; year += 25) {
            var events = service().generate(EventType.BRIGHTEST_APPROACH, BodyId.EARTH, BodyId.MERCURY,
                LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC),
                LocalDate.of(Math.min(year + 25, 3000), 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(1));
            for (var event : events) {
                Instant peak = event.computedTimeUtc();
                for (int direction : new int[] {-1, 1})
                    assertThat(shoulderRise(peak, direction)).as("local shoulder at %s direction %s", peak, direction)
                        .isGreaterThanOrEqualTo(0.05);
                count++;
            }
        }
        assertThat(count).isEqualTo(4412); // Pin retained peaks, not just a permissive lower bound.
    }

    @Test void venusBrightnessCountAcrossTheSupportedEpochCannotSilentlyLoseRealPeaks() {
        int count = 0;
        for (int year = 1600; year < 3000; year += 25)
            count += service().generate(EventType.BRIGHTEST_APPROACH, BodyId.EARTH, BodyId.VENUS,
                Instant.parse(year + "-01-01T00:00:00Z"),
                Instant.parse(Math.min(year + 25, 3000) + "-01-01T00:00:00Z").minusSeconds(1)).size();
        assertThat(count).isEqualTo(1751); // Regression count; independent dates are checked below.
    }

    @Test void venusBrightnessRetainsEveryReferencePeakFrom2020Through2035() {
        // astronomy-engine 2.1.19 SearchPeakMagnitude(Venus); each next search starts one day later.
        var references = List.of("2020-04-30", "2020-07-07", "2021-12-07", "2022-02-09",
            "2023-07-10", "2023-09-17", "2025-02-17", "2025-04-24", "2026-09-22", "2026-11-27",
            "2028-04-27", "2028-07-05", "2029-12-05", "2030-02-07", "2031-07-08", "2031-09-14",
            "2033-02-15", "2033-04-21", "2034-09-20", "2034-11-24");
        var events = service().generate(EventType.BRIGHTEST_APPROACH, BodyId.EARTH, BodyId.VENUS,
            Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2036-01-01T00:00:00Z"));
        assertThat(events).hasSameSizeAs(references);
        for (int i = 0; i < references.size(); i++)
            assertThat(Duration.between(Instant.parse(references.get(i) + "T00:00:00Z"), events.get(i).computedTimeUtc()).abs())
                .as(references.get(i)).isLessThan(Duration.ofDays(4)); // approximation, not Horizons precision
    }

    @org.junit.jupiter.params.provider.EnumSource(value = BodyId.class,
        names = {"MERCURY", "VENUS", "MARS", "JUPITER", "SATURN", "URANUS", "NEPTUNE"})
    @ParameterizedTest
    void everyPlanetaryStationaryIsInsideItsDailyBracketAndWithinAnHourOfTheInstantaneousZero(BodyId body) {
        int count = 0;
        for (int year = 1600; year < 3000; year += 25) {
            var events = service().generate(EventType.STATIONARY, BodyId.EARTH, body,
                Instant.parse(year + "-01-01T00:00:00Z"),
                Instant.parse(Math.min(year + 25, 3000) + "-01-01T00:00:00Z").minusSeconds(1));
            for (var event : events) {
                Instant time = event.computedTimeUtc();
                double before = instantaneousRate(body, time.minusSeconds(3600));
                double after = instantaneousRate(body, time.plusSeconds(3600));
                assertThat(before * after).as("%s at %s", body, time).isLessThanOrEqualTo(0);
                Instant low = Instant.ofEpochSecond(Math.floorDiv(time.getEpochSecond() - 43200, 86400) * 86400 + 43200);
                assertThat(time).isBetween(low, low.plusSeconds(86400));
                double lowRate = centredRate(body, low, 43200);
                double highRate = centredRate(body, low.plusSeconds(86400), 43200);
                assertThat(lowRate * highRate).as("daily bracket %s at %s", body, time).isLessThanOrEqualTo(0);
                count++;
            }
        }
        assertThat(count).isGreaterThan(1000);
    }

    private double instantaneousRate(BodyId body, Instant time) { return centredRate(body, time, 30); }
    private double centredRate(BodyId body, Instant time, long halfStep) {
        double difference = longitude(body, time.plusSeconds(halfStep)) - longitude(body, time.minusSeconds(halfStep));
        return Math.atan2(Math.sin(difference), Math.cos(difference));
    }
    private double longitude(BodyId body, Instant time) {
        var position = model.heliocentricPosition(body, time).minus(model.heliocentricPosition(BodyId.EARTH, time));
        return Math.atan2(position.y(), position.x());
    }

    private double magnitude(Instant time) {
        var earth = model.heliocentricPosition(BodyId.EARTH, time);
        var planet = model.heliocentricPosition(BodyId.MERCURY, time);
        return photometry.computeVisualMagnitude(BodyId.MERCURY, planet.magnitude(), planet.distanceTo(earth),
            planet.negate().angleBetweenDeg(earth.minus(planet)));
    }

    private double shoulderRise(Instant peak, int direction) {
        double minimum = magnitude(peak), previous = minimum, maximum = minimum;
        for (int halfDay = 1; halfDay <= 240; halfDay++) {
            double value = magnitude(peak.plusSeconds(direction * halfDay * 43200L));
            if (value < previous - 1e-6) break; // First local shoulder, not a distant brighter event.
            maximum = Math.max(maximum, value);
            previous = value;
        }
        return maximum - minimum;
    }

    @ParameterizedTest @ValueSource(strings = {"2298-02-11", "2437-01-28"})
    void mercuryStationaryRefinementStaysNearTheModelsExactRateZero(String date) {
        Instant day = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant low = day.minus(Duration.ofDays(2)), high = day.plus(Duration.ofDays(2));
        double lowRate = instantaneousRate(low);
        assertThat(lowRate * instantaneousRate(high)).isNegative();
        // Independent one-minute derivative approximates the instantaneous zero.
        for (int i = 0; i < 30; i++) {
            Instant mid = low.plus(Duration.between(low, high).dividedBy(2));
            double rate = instantaneousRate(mid);
            if (lowRate * rate <= 0) high = mid;
            else { low = mid; lowRate = rate; }
        }
        Instant expected = low.plus(Duration.between(low, high).dividedBy(2));
        for (var type : List.of(EventType.STATIONARY, EventType.RETROGRADE_START, EventType.RETROGRADE_END)) {
            var events = service().generate(type, BodyId.EARTH, BodyId.MERCURY,
                day.minus(Duration.ofDays(4)), day.plus(Duration.ofDays(4)));
            if (type == EventType.STATIONARY) assertThat(events).hasSize(1);
            for (var event : events) assertThat(Duration.between(expected, event.computedTimeUtc()).abs())
                .as(type + " at " + date).isLessThan(Duration.ofHours(1));
        }
    }

    private double instantaneousRate(Instant time) {
        double difference = longitude(time.plusSeconds(30)) - longitude(time.minusSeconds(30));
        return Math.atan2(Math.sin(difference), Math.cos(difference));
    }
    private double longitude(Instant time) {
        var position = model.heliocentricPosition(BodyId.MERCURY, time)
            .minus(model.heliocentricPosition(BodyId.EARTH, time));
        return Math.atan2(position.y(), position.x());
    }
}

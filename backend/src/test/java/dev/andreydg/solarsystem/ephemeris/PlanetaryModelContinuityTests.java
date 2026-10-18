package dev.andreydg.solarsystem.ephemeris;

import static org.assertj.core.api.Assertions.assertThat;
import dev.andreydg.solarsystem.catalog.BodyId;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PlanetaryModelContinuityTests {
    private final KeplerianEphemerisProvider model = new KeplerianEphemerisProvider();

    @Test void everyPlanetMovesNormallyAcrossBothFormerSwitchesAndBothRampEdges() {
        var epoch = Instant.parse("2000-01-01T12:00:00Z");
        for (double century : new double[] {-2.1, -2, 0.5, 0.6}) {
            Instant seam = epoch.plusSeconds(Math.round(century * 36525 * 86400));
            for (BodyId body : BodyId.values()) if (!body.isSmallBody()) {
                double normal = Math.max(step(body, seam.minusSeconds(3600)), step(body, seam.plusSeconds(3600)));
                for (int offset = -2; offset <= 2; offset++)
                    assertThat(step(body, seam.plusSeconds(offset))).as("%s at %s offset %s", body, seam, offset)
                        .isLessThanOrEqualTo(normal * 1.01 + 1e-10);
            }
        }
    }

    @Test void hourlyMotionIsSmoothAcrossTheEntireRampsAndThirtyDayMargins() {
        for (double start : new double[] {-2.1, 0.5}) {
            Instant from = atCentury(start).minusSeconds(30L * 86400);
            Instant to = atCentury(start + 0.1).plusSeconds(30L * 86400);
            for (BodyId body : BodyId.values()) if (!body.isSmallBody() && body != BodyId.EARTH) {
                Vector3Au left = geocentric(body, from.minusSeconds(3600));
                Vector3Au middle = geocentric(body, from);
                double before = left.distanceTo(middle);
                Vector3Au right = geocentric(body, from.plusSeconds(3600));
                double current = middle.distanceTo(right);
                for (Instant time = from; time.isBefore(to); time = time.plusSeconds(3600)) {
                    Vector3Au next = geocentric(body, time.plusSeconds(7200));
                    double after = right.distanceTo(next);
                    // Compare full 3D displacement, not longitude alone, so a stationary
                    // longitude does not cause division by a near-zero reference.
                    double normal = (before + after) / 2;
                    assertThat(Math.abs(current - normal)).as("%s hourly motion at %s", body, time)
                        .isLessThanOrEqualTo(normal * 0.01 + 1e-10);
                    before = current; current = after; right = next;
                }
            }
        }
    }

    @Test void tenYearRampsStayOutsideTheNativeFitAndUseContinuousWeights() {
        // Fixed quartile weights pin placement, width and continuous (not 1%-stepped)
        // blending through the PUBLIC position path. Pure table evaluation below is only
        // the reference endpoint calculation, never a replacement for that path.
        double[][] samples = {
            {-2.11, 0}, {-2.1, 0}, {-2.075, 0.103515625}, {-2.05, 0.5},
            {-2.025, 0.896484375}, {-2, 1}, {-1.95, 1}, {0.45, 1}, {0.5, 1},
            {0.525, 0.896484375}, {0.55, 0.5}, {0.575, 0.103515625}, {0.6, 0}, {0.61, 0}
        };
        for (double[] sample : samples) for (BodyId body : BodyId.values()) if (!body.isSmallBody()) {
            var shortPosition = table(body, sample[0], false);
            var longPosition = table(body, sample[0], true);
            double weight = sample[1];
            var expected = new Vector3Au(
                longPosition.x() + weight * (shortPosition.x() - longPosition.x()),
                longPosition.y() + weight * (shortPosition.y() - longPosition.y()),
                longPosition.z() + weight * (shortPosition.z() - longPosition.z()));
            assertThat(model.heliocentricPosition(body, atCentury(sample[0])).distanceTo(expected))
                .as("%s at century %s", body, sample[0]).isLessThanOrEqualTo(1e-12);
        }
    }

    @Test void bothFormerInFitRampDecadesRemainExactlyTableOneEveryHour() {
        for (double start : new double[] {-2, 0.4})
            for (Instant time = atCentury(start); time.isBefore(atCentury(start + 0.1)); time = time.plusSeconds(3600))
                for (BodyId body : BodyId.values()) if (!body.isSmallBody()) {
                    double century = (time.getEpochSecond() - atCentury(0).getEpochSecond()) / 86400.0 / 36525.0;
                    assertThat(model.heliocentricPosition(body, time))
                        .as("%s native fit at %s", body, time).isEqualTo(table(body, century, false));
                }
    }

    private static Instant atCentury(double century) {
        return Instant.parse("2000-01-01T12:00:00Z").plusSeconds(Math.round(century * 36525 * 86400));
    }

    private Vector3Au table(BodyId body, double century, boolean longRange) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(model, "positionFromElements", body, century, longRange);
    }

    @Test void positionsAwayFromTransitionsPreserveBothOriginalTables() throws Exception {
        try (var input = getClass().getResourceAsStream("/jpl/planetary-table-baseline.csv")) {
            var rows = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("#")).toList();
            assertThat(rows).hasSize(40);
            for (String row : rows) {
                String[] fields = row.split(",");
                var expected = new Vector3Au(Double.parseDouble(fields[2]), Double.parseDouble(fields[3]), Double.parseDouble(fields[4]));
                // Math trig intrinsics may differ by a few ULPs across JDKs/architectures.
                // 1e-12 AU is about 15 cm, far below a table-selection regression.
                assertThat(model.heliocentricPosition(BodyId.valueOf(fields[0]), Instant.parse(fields[1])).distanceTo(expected))
                    .as(fields[0] + " " + fields[1]).isLessThanOrEqualTo(1e-12);
            }
        }
    }
    @Test void blendHasZeroSlopeAndCurvatureAtBothEnds() {
        for (double x : new double[] {0, 0.0001, 0.9999, 1}) {
            double value = org.springframework.test.util.ReflectionTestUtils.invokeMethod(model, "smoothStep", x);
            assertThat(value).isCloseTo(x < 0.5 ? 0.0 : 1.0, org.assertj.core.data.Offset.offset(2e-11));
        }
    }

    private Vector3Au geocentric(BodyId body, Instant time) {
        return model.heliocentricPosition(body, time).minus(model.heliocentricPosition(BodyId.EARTH, time));
    }
    private double step(BodyId body, Instant time) {
        return geocentric(body, time).distanceTo(geocentric(body, time.plusSeconds(1)));
    }
}

package dev.andreydg.solarsystem.ephemeris;

import dev.andreydg.solarsystem.catalog.BodyId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KeplerianEphemerisProvider implements EphemerisProvider {
    private static final Instant J2000 = Instant.parse("2000-01-01T12:00:00Z");
    private static final double DAYS_PER_JULIAN_CENTURY = 36525.0;
    private static final Map<BodyId, OrbitalElements> ELEMENTS = createElements();
    private static final Map<BodyId, OrbitalElements> LONG_ELEMENTS = createLongElements();

    @Override
    public Vector3Au heliocentricPosition(BodyId body, Instant timeUtc) {
        double centuries = julianCenturiesSinceJ2000(timeUtc);
        // Preserve Table 1 throughout its native fit (-2..0.5 centuries). Blend Cartesian
        // positions over ten-year C2 ramps just outside it, briefly extrapolating Table 1
        // for continuity; that extrapolation has no JPL accuracy guarantee.
        if (centuries >= -2 && centuries <= 0.5) return positionFromElements(body, centuries, false);
        double shortWeight = smoothStep((centuries + 2.1) / 0.1) * smoothStep((0.6 - centuries) / 0.1);
        if (shortWeight == 0) return positionFromElements(body, centuries, true);
        if (shortWeight == 1) return positionFromElements(body, centuries, false);
        var shortPosition = positionFromElements(body, centuries, false);
        var longPosition = positionFromElements(body, centuries, true);
        return new Vector3Au(
            longPosition.x() + shortWeight * (shortPosition.x() - longPosition.x()),
            longPosition.y() + shortWeight * (shortPosition.y() - longPosition.y()),
            longPosition.z() + shortWeight * (shortPosition.z() - longPosition.z()));
    }

    private static double smoothStep(double value) {
        double x = Math.max(0, Math.min(1, value));
        return x * x * x * (10 + x * (-15 + 6 * x));
    }

    private static Vector3Au positionFromElements(BodyId body, double centuries, boolean longRange) {
        OrbitalElements elements = (longRange ? LONG_ELEMENTS : ELEMENTS).get(body).at(centuries);
        double meanAnomaly = normalizeRadians(Math.toRadians(elements.meanLongitudeDeg - elements.longitudePerihelionDeg
            + (longRange ? longRangeCorrection(body, centuries) : 0)));
        double eccentricAnomaly = solveKepler(meanAnomaly, elements.eccentricity);

        double xPrime = elements.semiMajorAxisAu * (Math.cos(eccentricAnomaly) - elements.eccentricity);
        double yPrime = elements.semiMajorAxisAu
            * Math.sqrt(1 - elements.eccentricity * elements.eccentricity)
            * Math.sin(eccentricAnomaly);

        double argumentOfPerihelion = Math.toRadians(elements.longitudePerihelionDeg - elements.longitudeNodeDeg);
        double longitudeNode = Math.toRadians(elements.longitudeNodeDeg);
        double inclination = Math.toRadians(elements.inclinationDeg);

        double cosNode = Math.cos(longitudeNode);
        double sinNode = Math.sin(longitudeNode);
        double cosArg = Math.cos(argumentOfPerihelion);
        double sinArg = Math.sin(argumentOfPerihelion);
        double cosInclination = Math.cos(inclination);
        double sinInclination = Math.sin(inclination);

        double x = (cosNode * cosArg - sinNode * sinArg * cosInclination) * xPrime
            + (-cosNode * sinArg - sinNode * cosArg * cosInclination) * yPrime;
        double y = (sinNode * cosArg + cosNode * sinArg * cosInclination) * xPrime
            + (-sinNode * sinArg + cosNode * cosArg * cosInclination) * yPrime;
        double z = (sinArg * sinInclination) * xPrime + (cosArg * sinInclination) * yPrime;

        return new Vector3Au(x, y, z);
    }

    @Override
    public String source() {
        return "KEPLERIAN_APPROX";
    }

    private static double julianCenturiesSinceJ2000(Instant timeUtc) {
        double days = J2000.until(timeUtc, ChronoUnit.SECONDS) / 86_400.0;
        return days / DAYS_PER_JULIAN_CENTURY;
    }

    private static double solveKepler(double meanAnomaly, double eccentricity) {
        double eccentricAnomaly = meanAnomaly;

        for (int i = 0; i < 12; i++) {
            double delta = (eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly) - meanAnomaly)
                / (1 - eccentricity * Math.cos(eccentricAnomaly));
            eccentricAnomaly -= delta;

            if (Math.abs(delta) < 1e-12) {
                break;
            }
        }

        return eccentricAnomaly;
    }

    private static double normalizeRadians(double radians) {
        double fullTurn = Math.PI * 2;
        double normalized = radians % fullTurn;
        return normalized < 0 ? normalized + fullTurn : normalized;
    }

    private static Map<BodyId, OrbitalElements> createElements() {
        Map<BodyId, OrbitalElements> elements = new EnumMap<>(BodyId.class);
        elements.put(BodyId.MERCURY, new OrbitalElements(
            0.38709927, 0.00000037,
            0.20563593, 0.00001906,
            7.00497902, -0.00594749,
            252.25032350, 149472.67411175,
            77.45779628, 0.16047689,
            48.33076593, -0.12534081
        ));
        elements.put(BodyId.VENUS, new OrbitalElements(
            0.72333566, 0.00000390,
            0.00677672, -0.00004107,
            3.39467605, -0.00078890,
            181.97909950, 58517.81538729,
            131.60246718, 0.00268329,
            76.67984255, -0.27769418
        ));
        elements.put(BodyId.EARTH, new OrbitalElements(
            1.00000261, 0.00000562,
            0.01671123, -0.00004392,
            -0.00001531, -0.01294668,
            100.46457166, 35999.37244981,
            102.93768193, 0.32327364,
            0.0, 0.0
        ));
        elements.put(BodyId.MARS, new OrbitalElements(
            1.52371034, 0.00001847,
            0.09339410, 0.00007882,
            1.84969142, -0.00813131,
            -4.55343205, 19140.30268499,
            -23.94362959, 0.44441088,
            49.55953891, -0.29257343
        ));
        elements.put(BodyId.JUPITER, new OrbitalElements(
            5.20288700, -0.00011607,
            0.04838624, -0.00013253,
            1.30439695, -0.00183714,
            34.39644051, 3034.74612775,
            14.72847983, 0.21252668,
            100.47390909, 0.20469106
        ));
        elements.put(BodyId.SATURN, new OrbitalElements(
            9.53667594, -0.00125060,
            0.05386179, -0.00050991,
            2.48599187, 0.00193609,
            49.95424423, 1222.49362201,
            92.59887831, -0.41897216,
            113.66242448, -0.28867794
        ));
        elements.put(BodyId.URANUS, new OrbitalElements(
            19.18916464, -0.00196176,
            0.04725744, -0.00004397,
            0.77263783, -0.00242939,
            313.23810451, 428.48202785,
            170.95427630, 0.40805281,
            74.01692503, 0.04240589
        ));
        elements.put(BodyId.NEPTUNE, new OrbitalElements(
            30.06992276, 0.00026291,
            0.00859048, 0.00005105,
            1.77004347, 0.00035372,
            -55.12002969, 218.45945325,
            44.96476227, -0.32241464,
            131.78422574, -0.00508664
        ));
        return Map.copyOf(elements);
    }

    // JPL approximate positions, Tables 2a/2b (valid -3000..3000; API supports 1600..2999).
    // https://ssd.jpl.nasa.gov/planets/approx_pos.html — these are NOT a full VSOP87 series.
    private static Map<BodyId, OrbitalElements> createLongElements() {
        return Map.of(
            BodyId.MERCURY, new OrbitalElements(0.38709843,0, 0.20563661,0.00002123, 7.00559432,-0.00590158, 252.25166724,149472.67486623, 77.45771895,0.15940013, 48.33961819,-0.12214182),
            BodyId.VENUS, new OrbitalElements(0.72332102,-0.00000026, 0.00676399,-0.00005107, 3.39777545,0.00043494, 181.97970850,58517.81560260, 131.76755713,0.05679648, 76.67261496,-0.27274174),
            BodyId.EARTH, new OrbitalElements(1.00000018,-0.00000003, 0.01673163,-0.00003661, -0.00054346,-0.01337178, 100.46691572,35999.37306329, 102.93005885,0.31795260, -5.11260389,-0.24123856),
            BodyId.MARS, new OrbitalElements(1.52371243,0.00000097, 0.09336511,0.00009149, 1.85181869,-0.00724757, -4.56813164,19140.29934243, -23.91744784,0.45223625, 49.71320984,-0.26852431),
            BodyId.JUPITER, new OrbitalElements(5.20248019,-0.00002864, 0.04853590,0.00018026, 1.29861416,-0.00322699, 34.33479152,3034.90371757, 14.27495244,0.18199196, 100.29282654,0.13024619),
            BodyId.SATURN, new OrbitalElements(9.54149883,-0.00003065, 0.05550825,-0.00032044, 2.49424102,0.00451969, 50.07571329,1222.11494724, 92.86136063,0.54179478, 113.63998702,-0.25015002),
            BodyId.URANUS, new OrbitalElements(19.18797948,-0.00020455, 0.04685740,-0.00001550, 0.77298127,-0.00180155, 314.20276625,428.49512595, 172.43404441,0.09266985, 73.96250215,0.05739699),
            BodyId.NEPTUNE, new OrbitalElements(30.06952752,0.00006447, 0.00895439,0.00000818, 1.77005520,0.00022400, 304.22289287,218.46515314, 46.68158724,0.01009938, 131.78635853,-0.00606302));
    }
    private static double longRangeCorrection(BodyId body, double t) {
        return switch (body) {
            case JUPITER -> -0.00012452 * t * t + 0.06064060 * Math.cos(Math.toRadians(38.35125 * t)) - 0.35635438 * Math.sin(Math.toRadians(38.35125 * t));
            case SATURN -> 0.00025899 * t * t - 0.13434469 * Math.cos(Math.toRadians(38.35125 * t)) + 0.87320147 * Math.sin(Math.toRadians(38.35125 * t));
            case URANUS -> 0.00058331 * t * t - 0.97731848 * Math.cos(Math.toRadians(7.67025 * t)) + 0.17689245 * Math.sin(Math.toRadians(7.67025 * t));
            case NEPTUNE -> -0.00041348 * t * t + 0.68346318 * Math.cos(Math.toRadians(7.67025 * t)) - 0.10162547 * Math.sin(Math.toRadians(7.67025 * t));
            default -> 0;
        };
    }

    private record OrbitalElements(
        double semiMajorAxisAu,
        double semiMajorAxisRate,
        double eccentricity,
        double eccentricityRate,
        double inclinationDeg,
        double inclinationRate,
        double meanLongitudeDeg,
        double meanLongitudeRate,
        double longitudePerihelionDeg,
        double longitudePerihelionRate,
        double longitudeNodeDeg,
        double longitudeNodeRate
    ) {
        OrbitalElements at(double centuries) {
            return new OrbitalElements(
                semiMajorAxisAu + semiMajorAxisRate * centuries,
                semiMajorAxisRate,
                eccentricity + eccentricityRate * centuries,
                eccentricityRate,
                inclinationDeg + inclinationRate * centuries,
                inclinationRate,
                meanLongitudeDeg + meanLongitudeRate * centuries,
                meanLongitudeRate,
                longitudePerihelionDeg + longitudePerihelionRate * centuries,
                longitudePerihelionRate,
                longitudeNodeDeg + longitudeNodeRate * centuries,
                longitudeNodeRate
            );
        }
    }
}

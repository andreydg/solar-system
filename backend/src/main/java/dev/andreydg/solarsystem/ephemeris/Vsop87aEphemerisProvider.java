package dev.andreydg.solarsystem.ephemeris;

import dev.andreydg.solarsystem.catalog.BodyId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class Vsop87aEphemerisProvider implements EphemerisProvider {
    private static final Instant J2000 = Instant.parse("2000-01-01T12:00:00Z");
    private static final double DAYS_PER_JULIAN_CENTURY = 36525.0;
    private static final Map<BodyId, OrbitalElements> ELEMENTS = createElements();

    @Override
    public Vector3Au heliocentricPosition(BodyId body, Instant timeUtc) {
        OrbitalElements elements = ELEMENTS.get(body).at(julianCenturiesSinceJ2000(timeUtc));
        double meanAnomaly = normalizeRadians(Math.toRadians(elements.meanLongitudeDeg - elements.longitudePerihelionDeg));
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
        return "VSOP87A_APPROX";
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

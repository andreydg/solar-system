package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.EventType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Acceptance rules operate on JPL samples, not on the provisional event's asserted geometry. */
final class EventGeometry {
    record Sample(Instant time, double value) {}
    private EventGeometry() {}

    static Sample locate(EventType type, List<Sample> samples, Instant anchor, boolean refined) {
        check(samples);
        return switch (type) {
            case OPPOSITION, CONJUNCTION -> crossing(samples, anchor, type == EventType.OPPOSITION ? 180 : 0,
                0, true, refined ? 0.02 : 180);
            case STATIONARY, RETROGRADE_START, RETROGRADE_END -> crossing(rates(samples), anchor, 0,
                type == EventType.RETROGRADE_START ? -1 : type == EventType.RETROGRADE_END ? 1 : 0,
                false, refined ? 0.01 : Double.POSITIVE_INFINITY);
            default -> extremum(samples, anchor, type != EventType.FARTHEST_APPROACH && type != EventType.GREATEST_ELONGATION);
        };
    }
    private static Sample extremum(List<Sample> samples, Instant anchor, boolean minimum) {
        var candidates = new ArrayList<Sample>();
        for (int i = 1; i < samples.size() - 1; i++) {
            double before = samples.get(i - 1).value(), value = samples.get(i).value(), after = samples.get(i + 1).value();
            boolean bracket = minimum ? value <= before && value <= after && (value < before || value < after)
                : value >= before && value >= after && (value > before || value > after);
            if (bracket) candidates.add(samples.get(i));
        }
        return nearest(candidates, anchor);
    }
    private static Sample crossing(List<Sample> samples, Instant anchor, double target, int direction, boolean angular, double tolerance) {
        var candidates = new ArrayList<Sample>();
        for (int i = 1; i < samples.size(); i++) {
            var left = samples.get(i - 1); var right = samples.get(i);
            double a = offset(left.value(), target, angular), b = offset(right.value(), target, angular);
            Instant exactRoot = null;
            // A zero sample must actually have opposite non-zero signs on its two sides.
            if (b == 0 && i + 1 < samples.size()) {
                exactRoot = right.time();
                right = samples.get(i + 1); b = offset(right.value(), target, angular);
            }
            if (a == 0 || b == 0 || Math.signum(a) == Math.signum(b)) continue;
            if (angular && Math.abs(a - b) >= 180) continue; // longitude wrap is not an event
            if (direction < 0 && !(a > 0 && b < 0) || direction > 0 && !(a < 0 && b > 0)) continue;
            if (exactRoot == null && Math.min(Math.abs(a), Math.abs(b)) > tolerance) continue;
            double fraction = Math.abs(a) / (Math.abs(a) + Math.abs(b));
            Instant root = exactRoot != null ? exactRoot : left.time().plusMillis(Math.round(Duration.between(left.time(), right.time()).toMillis() * fraction));
            candidates.add(new Sample(root.truncatedTo(ChronoUnit.SECONDS), target));
        }
        return nearest(candidates, anchor);
    }
    private static List<Sample> rates(List<Sample> positions) {
        var samples = new ArrayList<Sample>();
        for (int i = 1; i < positions.size(); i++) {
            var left = positions.get(i - 1); var right = positions.get(i);
            double days = Duration.between(left.time(), right.time()).toMillis() / 86_400_000.0;
            samples.add(new Sample(left.time().plusMillis(Duration.between(left.time(), right.time()).toMillis() / 2),
                signedAngle(right.value() - left.value()) / days));
        }
        return samples;
    }
    private static double offset(double value, double target, boolean angular) { return angular ? signedAngle(value - target) : value - target; }
    static double signedAngle(double angle) { double n = ((angle % 360) + 360) % 360; return n > 180 ? n - 360 : n; }
    private static Sample nearest(List<Sample> candidates, Instant anchor) {
        return candidates.stream().min(Comparator.comparingLong(sample -> Math.abs(Duration.between(anchor, sample.time()).toMillis())))
            .orElseThrow(() -> new JplHorizonsException("JPL samples do not bracket the requested event with the required geometry/direction"));
    }
    private static void check(List<Sample> samples) {
        if (samples.size() < 3) throw new JplHorizonsException("Insufficient JPL samples for event validation");
        for (int i = 0; i < samples.size(); i++) {
            if (!Double.isFinite(samples.get(i).value()) || i > 0 && !samples.get(i).time().isAfter(samples.get(i - 1).time())) {
                throw new JplHorizonsException("Invalid or unordered JPL samples");
            }
        }
    }
}

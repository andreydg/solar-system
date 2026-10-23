package dev.andreydg.solarsystem.jpl;

import java.time.Duration;

/** Stops starting JPL calls before the request deadline; each in-flight call has a 15s read timeout. */
public final class ValidationBudget implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private ValidationBudget(Duration duration) { DEADLINE.set(System.nanoTime() + duration.toNanos()); }
    static ValidationBudget start(Duration duration) { return new ValidationBudget(duration); }
    static boolean isActive() { return DEADLINE.get() != null; }
    public static boolean hasTime() {
        Long deadline = DEADLINE.get();
        return !Thread.currentThread().isInterrupted() && (deadline == null || System.nanoTime() < deadline);
    }
    static boolean hasTimeFor(Duration work) {
        Long deadline = DEADLINE.get();
        return hasTime() && (deadline == null || deadline - System.nanoTime() >= work.toNanos());
    }
    static final class Exhausted extends JplHorizonsException {
        Exhausted() { super("Validation request time budget exceeded", true); }
    }
    static void check() {
        if (!hasTime()) throw new Exhausted();
    }
    @Override public void close() { DEADLINE.remove(); }
}

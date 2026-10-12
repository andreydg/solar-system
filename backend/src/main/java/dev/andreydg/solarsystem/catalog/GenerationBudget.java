package dev.andreydg.solarsystem.catalog;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Cooperative deadline: checked at each ephemeris evaluation, with no orphan background task. */
public final class GenerationBudget implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private GenerationBudget(Duration duration) { DEADLINE.set(System.nanoTime() + duration.toNanos()); }
    public static GenerationBudget start() { return new GenerationBudget(Duration.ofSeconds(30)); }
    public static void check() {
        Long end = DEADLINE.get();
        if (Thread.currentThread().isInterrupted() || (end != null && System.nanoTime() - end >= 0)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Generation time budget exceeded; try a smaller window");
        }
    }
    @Override public void close() { DEADLINE.remove(); }
}

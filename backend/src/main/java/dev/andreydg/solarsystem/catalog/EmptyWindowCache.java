package dev.andreydg.solarsystem.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Short-lived, bounded negative cache. Never caches provisional event contents. */
final class EmptyWindowCache {
    private final Clock clock;
    private final LinkedHashMap<Key, Instant> windows = new LinkedHashMap<>();
    EmptyWindowCache(Clock clock) { this.clock = clock; }
    synchronized boolean contains(Key key) {
        windows.values().removeIf(expiry -> !expiry.isAfter(clock.instant()));
        return windows.containsKey(key);
    }
    synchronized void remember(Key key) {
        windows.put(key, clock.instant().plus(Duration.ofMinutes(10)));
        while (windows.size() > 256) windows.remove(windows.keySet().iterator().next());
    }
    record Key(EventType type, String pair, Instant from, Instant to) {}
}

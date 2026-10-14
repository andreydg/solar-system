package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class EmptyWindowCacheTests {
    @Test void expiresAndEvictsWithoutUnboundedMemory() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var cache = new EmptyWindowCache(clock);
        var first = key(0);
        cache.remember(first);
        assertThat(cache.contains(first)).isTrue();
        for (int i = 1; i <= 256; i++) cache.remember(key(i));
        assertThat(cache.contains(first)).isFalse();
        assertThat(cache.contains(key(256))).isTrue();
        now.set(now.get().plusSeconds(600));
        assertThat(cache.contains(key(256))).isFalse();
    }
    private EmptyWindowCache.Key key(int offset) {
        return new EmptyWindowCache.Key(EventType.TRANSIT, "earth:venus", Instant.EPOCH.plusSeconds(offset), Instant.EPOCH.plusSeconds(offset + 100));
    }
}

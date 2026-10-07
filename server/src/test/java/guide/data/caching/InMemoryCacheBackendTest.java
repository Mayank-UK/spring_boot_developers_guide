package guide.data.caching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The fake backend's contract, pinned with clock ARITHMETIC (topic 20's
 * lazy-expiry model, applied again): an entry dies at its deadline and is
 * simply ABSENT on the next access; nothing reaps at the moment of expiry;
 * TTLs must be positive (an entry without an expiry is an immortal leak);
 * evict is DEL-shaped (safe unconditionally); and an expired entry leaves
 * no residue — a re-put under the same key works immediately.
 */
class InMemoryCacheBackendTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final InMemoryCacheBackend backend = new InMemoryCacheBackend(now::get);

    @Test
    void entriesExpireLazilyAtTheirDeadline() {
        backend.put("k", "v", Duration.ofSeconds(60));
        assertTrue(backend.get("k").isPresent());

        now.addAndGet(59_999);
        assertTrue(backend.get("k").isPresent(), "one millisecond before the deadline it lives");

        now.addAndGet(1);
        assertTrue(backend.get("k").isEmpty(),
                "at the deadline it is absent — lazy expiry, a miss is a miss");
    }

    @Test
    void anExpiredKeyCanBeRefilledImmediately() {
        backend.put("k", "first", Duration.ofSeconds(10));
        now.addAndGet(11_000);
        assertTrue(backend.get("k").isEmpty());

        backend.put("k", "second", Duration.ofSeconds(10));
        assertEquals("second", backend.get("k").orElseThrow());
    }

    @Test
    void putsMustCarryAPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> backend.put("k", "v", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> backend.put("k", "v", Duration.ofSeconds(-5)));
        assertThrows(NullPointerException.class,
                () -> backend.put("k", "v", null));
    }

    @Test
    void evictIsSafeWhetherOrNotTheKeyExists() {
        backend.evict("ghost");
        backend.put("k", "v", Duration.ofSeconds(60));
        backend.evict("k");
        assertTrue(backend.get("k").isEmpty());
        backend.evict("k");
    }

    @Test
    void clearEmptiesEverythingForTheNextTestOrDemo() {
        backend.put("a", "1", Duration.ofSeconds(60));
        backend.put("b", "2", Duration.ofSeconds(60));
        backend.clear();
        assertTrue(backend.get("a").isEmpty());
        assertTrue(backend.get("b").isEmpty());
    }
}

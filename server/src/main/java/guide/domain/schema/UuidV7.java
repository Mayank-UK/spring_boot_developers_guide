package guide.domain.schema;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * A conforming uuid version 7 generator (RFC 9562): 48 bits of Unix
 * millisecond timestamp in the most significant bits, then the version
 * nibble (0111), then 12 bits of per-millisecond counter, then the RFC
 * variant plus 62 random bits. Java's standard library still ships no v7
 * constructor (UUID.randomUUID is v4 only), so the guide carries its own —
 * small enough to read in one sitting, which is itself the lesson: v7 is a
 * LAYOUT, not a service.
 *
 * The bit every naive implementation gets wrong: two ids minted in the SAME
 * millisecond must still sort monotonically, or the "time-ordered" promise
 * quietly evaporates under a fast loop. The 12-bit rand_a field is therefore
 * used as a counter within the millisecond (incremented under the same lock
 * that mints the id): timestamps dominate the high bits, the counter
 * tie-breaks inside one millisecond, and after 4096 ids in a single
 * millisecond the generator waits for the clock to advance rather than wrap
 * (a wrap would re-sort older-than-random and break monotonicity — the exact
 * bug the demo exists to demonstrate in v4).
 *
 * Thread safety: synchronized newId() — correctness first; audit of the
 * cost is topic 38's problem, not a schema demo's.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final long MILLIS_MASK = 0xFFFFFFFFFFFFL;
    private static final long COUNTER_MASK = 0xFFFL;
    private static final long COUNTER_MAX = COUNTER_MASK;

    /** The last millisecond minted, guarded by UuidV7.class. */
    private static long lastMillis;
    /** The counter value used inside lastMillis (already consumed). */
    private static long lastCounter;

    private UuidV7() {
    }

    /** One time-ordered uuid: strictly increasing within this process. */
    public static synchronized UUID newId() {
        long millis = System.currentTimeMillis();
        long counter;
        if (millis > lastMillis) {
            counter = RANDOM.nextInt(0, 4096);
        } else {
            // Same tick, or the clock ran backwards (NTP): clamp to the last
            // millisecond so ids never regress; the counter keeps increasing.
            millis = lastMillis;
            if (lastCounter >= COUNTER_MAX) {
                // 4096 ids in one millisecond: wait for the next tick rather
                // than wrap — a wrap would re-sort mid-millisecond ids and
                // break monotonicity, the exact bug v4 exists to demonstrate.
                while ((millis = System.currentTimeMillis()) <= lastMillis) {
                    Thread.onSpinWait();
                }
                counter = RANDOM.nextInt(0, 4096);
            } else {
                counter = lastCounter + 1;
            }
        }
        lastMillis = millis;
        lastCounter = counter;

        long msb = (millis & MILLIS_MASK) << 16        // 48 timestamp bits
                | (0x7L << 12)                          // version 7
                | (counter & COUNTER_MASK);             // monotonic counter
        long lsb = RANDOM.nextLong()
                & 0x3FFFFFFFFFFFFFFFL                   // 62 random bits
                | 0x8000000000000000L;                  // RFC variant (10xx)
        return new UUID(msb, lsb);
    }
}

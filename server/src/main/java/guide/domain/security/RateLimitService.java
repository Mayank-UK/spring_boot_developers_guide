package guide.domain.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rate limiter (topic 25): per-key token buckets, in memory, built on
 * Bucket4j. One bucket per (key, limit, window) signature — the demo lets a
 * caller PROVISION a family ("5 per 60 seconds for api-key demo-1"), and
 * every call with the same signature shares that bucket.
 *
 * The algorithm is a token bucket, chosen honestly: capacity C tokens,
 * refilled continuously (greedy) at C/window — bursts up to C pass
 * immediately, the sustained rate converges on C per window. Fixed-window
 * counting has the boundary burst flaw (2x C at a window edge); sliding
 * window logs fix that at bookkeeping cost. For "how many attempts per
 * minute may one identity make" the token bucket is the right default —
 * and the 429 + Retry-After contract below is the part callers feel.
 *
 * In-memory means per-instance: fine for a demo and honest about the limit —
 * a fleet behind a load balancer needs the SAME bucket family everywhere,
 * which is what Bucket4j's distributed backends (Redis, Hazelcast…) are for.
 * The map key carries the full signature, so different limit/window choices
 * never share state.
 */
public final class RateLimitService {

    /** One consumption attempt: allowed with remaining budget, or refused with how long to wait. */
    public record Consumption(boolean allowed, long remainingTokens, long retryAfterSeconds) {

        static Consumption refused(long retryAfterSeconds) {
            return new Consumption(false, 0, retryAfterSeconds);
        }
    }

    /** A peek that consumes nothing — the demo's status read. */
    public record Status(long limit, long windowSeconds, long availableTokens) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Try to spend one token for the key; the bucket is created on first use. */
    public Consumption tryConsume(String key, long limit, Duration window) {
        Bucket bucket = bucket(key, limit, window);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Consumption(true, probe.getRemainingTokens(), 0);
        }
        long retryAfterSeconds = Math.max(1,
                Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        return Consumption.refused(retryAfterSeconds);
    }

    /** Read the bucket's state without touching it. */
    public Status status(String key, long limit, Duration window) {
        Bucket bucket = buckets.get(signature(key, limit, window));
        long available = bucket == null ? limit : bucket.getAvailableTokens();
        return new Status(limit, window.toSeconds(), available);
    }

    /** The reset seam: forget the bucket so the next call starts full. Tests and the demo's reset button. */
    public void reset(String key, long limit, Duration window) {
        buckets.remove(signature(key, limit, window));
    }

    private Bucket bucket(String key, long limit, Duration window) {
        return buckets.computeIfAbsent(signature(key, limit, window), ignored -> Bucket.builder()
                .addLimit(Bandwidth.classic(limit, Refill.greedy(limit, window)))
                .build());
    }

    private static String signature(String key, long limit, Duration window) {
        return key + "|" + limit + "|" + window;
    }
}

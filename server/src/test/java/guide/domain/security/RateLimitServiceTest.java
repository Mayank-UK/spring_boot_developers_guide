package guide.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The token-bucket limiter, deterministic by construction: windows are long
 * (an hour) so refill cannot rescue an exhausted bucket mid-test, and every
 * test uses its own key so no state leaks between cases. What is pinned:
 * the budget degrades, refusal carries a Retry-After, keys are independent,
 * and the reset seam really resets.
 */
class RateLimitServiceTest {

    private static final Duration LONG_WINDOW = Duration.ofHours(1);

    private final RateLimitService limiter = new RateLimitService();

    @Test
    void consumptionDepletesTheBudgetAndThenRefusesWithRetryAfter() {
        // 3 per hour: three pass, the fourth is refused — no sleeps needed
        assertTrue(limiter.tryConsume("login:alice", 3, LONG_WINDOW).allowed());
        assertEquals(1, limiter.tryConsume("login:alice", 3, LONG_WINDOW).remainingTokens(),
                "the second consume spends another token: 3 - 2 = 1 left");
        assertTrue(limiter.tryConsume("login:alice", 3, LONG_WINDOW).allowed());

        RateLimitService.Consumption refused = limiter.tryConsume("login:alice", 3, LONG_WINDOW);
        assertFalse(refused.allowed());
        assertTrue(refused.retryAfterSeconds() >= 1,
                "Retry-After is at least one second (never zero for a caller)");
    }

    @Test
    void keysAreIndependentBuckets() {
        assertTrue(limiter.tryConsume("ip:1.2.3.4", 1, LONG_WINDOW).allowed());
        assertTrue(limiter.tryConsume("ip:5.6.7.8", 1, LONG_WINDOW).allowed(),
                "a different key has its own budget");
        assertFalse(limiter.tryConsume("ip:1.2.3.4", 1, LONG_WINDOW).allowed());
    }

    @Test
    void limitAndWindowArePartOfTheBucketSignature() {
        assertTrue(limiter.tryConsume("demo", 1, LONG_WINDOW).allowed());
        assertFalse(limiter.tryConsume("demo", 1, LONG_WINDOW).allowed());
        // same key, different family (the demo provisions its own limits)
        assertTrue(limiter.tryConsume("demo", 5, Duration.ofSeconds(60)).allowed());
    }

    @Test
    void statusPeeksWithoutConsuming() {
        limiter.tryConsume("peek", 2, LONG_WINDOW);
        RateLimitService.Status status = limiter.status("peek", 2, LONG_WINDOW);
        assertEquals(1, status.availableTokens());
        // the peek itself spent nothing
        assertTrue(limiter.tryConsume("peek", 2, LONG_WINDOW).allowed());
    }

    @Test
    void anUnknownKeysStatusIsAFullBucket() {
        RateLimitService.Status status = limiter.status("never-seen", 7, Duration.ofSeconds(30));
        assertEquals(7, status.availableTokens());
        assertEquals(30, status.windowSeconds());
    }

    @Test
    void theResetSeamRestoresAFullBudget() {
        limiter.tryConsume("reset-me", 1, LONG_WINDOW);
        assertFalse(limiter.tryConsume("reset-me", 1, LONG_WINDOW).allowed());
        limiter.reset("reset-me", 1, LONG_WINDOW);
        assertTrue(limiter.tryConsume("reset-me", 1, LONG_WINDOW).allowed(),
                "after reset the bucket starts full again");
    }
}

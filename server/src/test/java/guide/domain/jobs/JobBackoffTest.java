package guide.domain.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The backoff curve as pure math — the deterministic half of the retry
 * policy. No clock, no queue, no sleeping: growth, cap, jitter, and the
 * refusal to schedule a "backoff" before any failure exists.
 */
class JobBackoffTest {

    @Test
    void curveGrowsOneTwoFourThenStaysCapped() {
        assertEquals(Duration.ofSeconds(1), JobBackoff.delayAfter(1));
        assertEquals(Duration.ofSeconds(2), JobBackoff.delayAfter(2));
        assertEquals(Duration.ofSeconds(4), JobBackoff.delayAfter(3));
        assertEquals(Duration.ofSeconds(4), JobBackoff.delayAfter(4));
        assertEquals(Duration.ofSeconds(4), JobBackoff.delayAfter(9));
    }

    @Test
    void backoffStartsAfterTheFirstFailure() {
        assertThrows(IllegalArgumentException.class, () -> JobBackoff.delayAfter(0));
    }

    @Test
    void jitterIsAParameterSoZeroJitterIsExact() {
        Duration oneSecond = Duration.ofSeconds(1);
        assertEquals(oneSecond, JobBackoff.withJitter(oneSecond, () -> 0.0));
        assertEquals(Duration.ofMillis(1500), JobBackoff.withJitter(oneSecond, () -> 0.5));
        assertEquals(Duration.ofMillis(2000), JobBackoff.withJitter(oneSecond, () -> 1.0));
    }
}

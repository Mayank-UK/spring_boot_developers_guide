package guide.data.webhooks;

import guide.domain.webhooks.DeliveryEngine;
import java.time.Clock;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Adapter: the heartbeat that turns wall-clock time into engine progress.
 * One @Scheduled tick per second calls {@code attemptNextDue(now)} — the
 * engine decides what is due, so the scheduler itself carries no policy and
 * the same engine method serves tests with constructed instants instead.
 *
 * fixedDelay (not fixedRate) on purpose: a tick that overruns delays the
 * next one instead of stacking — with a 1s cadence and a bounded batch, the
 * loop can never wedge itself under slow consumers; deliveries simply wait
 * out their backoff, which is exactly what the backoff is for.
 */
public final class WebhookDeliveryTicker {

    private final DeliveryEngine engine;
    private final Clock clock;

    public WebhookDeliveryTicker(DeliveryEngine engine, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One beat: attempt everything whose nextAttemptAt has arrived. */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        engine.attemptNextDue(clock.instant());
    }
}

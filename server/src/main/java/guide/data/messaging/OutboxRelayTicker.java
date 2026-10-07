package guide.data.messaging;

import guide.domain.messaging.OutboxRelay;
import java.time.Clock;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The relay's heartbeat — the webhook ticker's exact shape (fixedDelay, not
 * fixedRate: an overrunning tick delays the next instead of stacking), and
 * the no-eager-consumers lesson as a bean: this ticker EXISTS from boot but
 * does NOTHING until live activation succeeded AND the msg area is in live
 * mode — {@code canRun()} is the OutboxRelay's gate, fed by the activation
 * flag and the ScenarioStore, so a fake-pinned area (or a not-yet-activated
 * process) costs one boolean read per second and never touches Redis or
 * PostgreSQL. Flip the area live after activation and the drain starts on
 * the next tick; flip it back and the outbox simply piles up — observable,
 * reversible, no restart. @EnableScheduling lives on JobsDemoConfig (topic
 * 29 relocated it from the webhooks config; idempotent annotation,
 * deliberately not repeated here).
 */
public final class OutboxRelayTicker {

    /** One batch per tick, bounded — a tick is a nudge, not a flood. */
    static final int BATCH = 16;

    private final OutboxRelay relay;
    private final Clock clock;

    public OutboxRelayTicker(OutboxRelay relay, Clock clock) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (relay.canRun()) {
            relay.relayNextBatch(clock.instant(), BATCH);
        }
    }
}

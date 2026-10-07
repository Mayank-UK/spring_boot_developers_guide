package guide.domain.realtime;

import java.time.Clock;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Adapter: the heartbeat that turns wall-clock time into feed progress —
 * the realtime twin of the webhooks ticker. One {@code @Scheduled} beat per
 * interval calls {@link TickerFeed#tick(Instant)}; THE FEED DECIDES what a
 * beat means (with zero listeners, tick is a no-op — this pump can beat all
 * day into a paused feed for free). Tests never involve this class: they
 * call {@code tick(now)} directly with constructed instants, which is why
 * the interval is a property and the clock is injected.
 *
 * fixedDelay (not fixedRate) for the same reason as the webhooks ticker: a
 * beat that overruns delays the next one instead of stacking beats. The
 * pace is property-injectable — {@code guide.demo.realtime.ticker-interval-ms}
 * (default 500ms) — so a demo or test deployment can slow the world down
 * without a rebuild.
 *
 * NOTE on @EnableScheduling: it lives app-wide (currently declared on
 * WebhooksDemoConfig); this class only contributes the beat.
 */
public final class TickerPump {

    private final TickerFeed feed;
    private final Clock clock;

    public TickerPump(TickerFeed feed, Clock clock) {
        this.feed = Objects.requireNonNull(feed, "feed");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One beat: one pace step, if anyone is listening. */
    @Scheduled(fixedDelayString = "${guide.demo.realtime.ticker-interval-ms:500}")
    public void beat() {
        feed.tick(clock.instant());
    }
}

package guide.config;

import guide.domain.realtime.TickerFeed;
import guide.domain.realtime.TickerPump;
import guide.presentation.realtime.ChatWebSocketHandler;
import java.time.Clock;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo wiring for topic 28 (realtime) — the ticker feed, its pump, and the
 * chat handler as explicit beans, constructor injection throughout.
 * Choices, same family as the webhooks wiring:
 *
 * - @EnableScheduling is NOT redeclared here: it is already app-wide
 *   (WebhooksDemoConfig declares it idempotently), and scheduling is only
 *   the PUMP's business anyway — the feed itself is driven by tick(now)
 *   calls, which is what makes it testable without any scheduler at all.
 * - The Clock stays a constructor argument (system UTC here), never a
 *   bean — the seam tests use with fakes, no bean-collision surface for
 *   sibling topics.
 * - The ObjectMapper is Boot's own: tick frames and chat frames serialize
 *   with the same mapper the rest of the app configured — one
 *   serialization policy, one opinion about what an Instant looks like.
 */
@Configuration
public class RealtimeDemoConfig {

    /** The feed: 3 symbols, injected randomness, paused until somebody subscribes. */
    @Bean
    TickerFeed tickerFeed() {
        return new TickerFeed(new Random()::nextDouble);
    }

    /** The heartbeat: wall-clock beats → feed.tick(now), pace property-injectable. */
    @Bean
    TickerPump tickerPump(TickerFeed tickerFeed) {
        return new TickerPump(tickerFeed, Clock.systemUTC());
    }

    /** The chat handler: rooms vocabulary + one ObjectMapper + one clock. */
    @Bean
    ChatWebSocketHandler chatWebSocketHandler(ObjectMapper objectMapper) {
        return new ChatWebSocketHandler(objectMapper, Clock.systemUTC());
    }
}

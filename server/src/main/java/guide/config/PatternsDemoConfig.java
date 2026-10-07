package guide.config;

import guide.domain.patterns.EventStore;
import guide.domain.patterns.OrderSagas;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 44 (advanced-architecture) wiring — the composition root for the
 * arch:saga and arch:eventstore demos. Two engines, both PURE DOMAIN (JDK
 * and guide.core only — the dependency rule holds for the patterns layer
 * exactly as it does everywhere else): an in-memory saga orchestrator and
 * an in-memory event store, deliberately free of infrastructure because the
 * patterns are the lesson, not the plumbing. A production saga would drive
 * steps over queues behind the same domain shape; a production event store
 * would add persistence, optimistic concurrency and snapshots behind the
 * same append/replay contract — in both cases this config is the single
 * place a wiring change lands.
 */
@Configuration
public class PatternsDemoConfig {

    @Bean
    OrderSagas orderSagas() {
        return new OrderSagas();
    }

    @Bean
    EventStore patternsEventStore() {
        return new EventStore();
    }
}

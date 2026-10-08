package guide.config;

import guide.data.jobs.InMemoryJobQueue;
import guide.data.jobs.JobsDemoReset;
import guide.data.jobs.RecordingEmailSender;
import guide.data.messaging.MessagingDemoReset;
import guide.data.messaging.RedisStreamMessaging;
import guide.domain.jobs.AsyncWorkBoard;
import guide.domain.messaging.EventCounters;
import guide.domain.patterns.EventStore;
import guide.domain.patterns.OrderSagas;
import guide.domain.patterns.PatternsDemoReset;
import guide.domain.reliability.ReliabilityDemoReset;
import guide.domain.reliability.ResetResilienceDemo;
import guide.domain.reset.ResetDemos;
import guide.domain.reset.ResettableDemo;
import guide.domain.scaling.ProbeStatelessness;
import guide.domain.scaling.ScalingDemoReset;
import guide.domain.thirdparty.ThirdPartyDemoReset;
import guide.domain.thirdparty.ThirdPartyHub;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guide infrastructure (not a curriculum topic): the composition root for
 * the SHARED demo reset — every mutable demo area registered behind one
 * port, POST /api/demo/reset. Areas that already owned a reset (patterns,
 * scaling, thirdparty, reliability) register by DELEGATING to the same
 * engine methods their pinned endpoints call; the two areas that had none
 * (messaging, jobs) get real adapters beside their stores. Registering a
 * new area is one bean here — the endpoint, the report shape, and the
 * ?areas= validation all derive from this list.
 */
@Configuration
public class DemoResetConfig {

    // ---- the two NEW resets (areas that never had one) -------------------------

    @Bean
    ResettableDemo messagingDemoReset(JdbcTemplate jdbc, StringRedisTemplate redis,
            RedisStreamMessaging stream, EventCounters counters) {
        return new MessagingDemoReset(jdbc, redis, stream, counters);
    }

    @Bean
    ResettableDemo jobsDemoReset(InMemoryJobQueue jobQueue, RecordingEmailSender emailSender,
            AsyncWorkBoard asyncWorkBoard) {
        return new JobsDemoReset(jobQueue, emailSender, asyncWorkBoard);
    }

    // ---- the DELEGATING registrations (existing resets, untouched) -------------

    @Bean
    ResettableDemo patternsDemoReset(OrderSagas orderSagas, EventStore patternsEventStore) {
        return new PatternsDemoReset(orderSagas, patternsEventStore);
    }

    @Bean
    ResettableDemo scalingDemoReset(ProbeStatelessness probeStatelessness) {
        return new ScalingDemoReset(probeStatelessness);
    }

    @Bean
    ResettableDemo thirdpartyDemoReset(ThirdPartyHub thirdPartyHub) {
        return new ThirdPartyDemoReset(thirdPartyHub);
    }

    @Bean
    ResettableDemo reliabilityDemoReset(ResetResilienceDemo resetResilienceDemo) {
        return new ReliabilityDemoReset(resetResilienceDemo);
    }

    // ---- the use case: every registered area, one sweep ------------------------

    @Bean
    ResetDemos resetDemos(List<ResettableDemo> resettableDemos) {
        return new ResetDemos(resettableDemos);
    }
}

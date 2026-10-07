package guide.config;

import guide.core.scenario.ScenarioStore;
import guide.data.reliability.Resilience4jBreakerAdapter;
import guide.domain.reliability.BreakerPort;
import guide.domain.reliability.CallDownstream;
import guide.domain.reliability.DownstreamTheater;
import guide.domain.reliability.ForceHalfOpenProbe;
import guide.domain.reliability.ReadBreakerStatus;
import guide.domain.reliability.ResilientCaller;
import guide.domain.reliability.ResetResilienceDemo;
import guide.domain.reliability.RetryPolicy;
import guide.domain.reliability.SetDownstreamMode;
import guide.domain.reliability.VirtualTime;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 39 (reliability) wiring — the reliability:breaker demo's
 * composition root. Exactly the ports-to-adapters shape every other area
 * of this app runs on, applied to a resilience library: the DOMAIN owns
 * {@link BreakerPort} (state, a call-through, a snapshot, the ops rails),
 * the ADAPTER under data/ owns the Resilience4j instance and its five
 * knobs, and this class is the one place they meet — the dependency rule
 * from topic 32, held by the build's DependencyRuleTest, is why the domain
 * never imports the library.
 *
 * The scenario rail: the app-wide failure injection switch
 * (PATCH /api/demo/scenario, injectFailure) is handed to the theater as a
 * pure BooleanSupplier — domain stays framework-free, and the chaos
 * section's "one door for every failure" claim is literally true.
 */
@Configuration
public class ReliabilityDemoConfig {

    @Bean
    VirtualTime reliabilityVirtualTime() {
        return new VirtualTime(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Bean
    RetryPolicy retryPolicy() {
        return new RetryPolicy();
    }

    @Bean
    DownstreamTheater downstreamTheater(ScenarioStore scenarioStore) {
        return new DownstreamTheater(() -> scenarioStore.get().injectFailure());
    }

    /** The breaker BEHIND the domain's port — the only bean that knows r4j exists. */
    @Bean
    BreakerPort reliabilityBreaker() {
        return new Resilience4jBreakerAdapter();
    }

    @Bean
    ResilientCaller resilientCaller(DownstreamTheater theater, BreakerPort reliabilityBreaker,
            RetryPolicy retryPolicy, VirtualTime reliabilityVirtualTime) {
        return new ResilientCaller(theater, reliabilityBreaker, retryPolicy, reliabilityVirtualTime);
    }

    @Bean
    ReadBreakerStatus readBreakerStatus(ResilientCaller resilientCaller, DownstreamTheater theater,
            VirtualTime reliabilityVirtualTime, BreakerPort reliabilityBreaker) {
        return new ReadBreakerStatus(resilientCaller, theater, reliabilityVirtualTime,
                reliabilityBreaker);
    }

    @Bean
    CallDownstream callDownstream(ResilientCaller resilientCaller) {
        return new CallDownstream(resilientCaller);
    }

    @Bean
    SetDownstreamMode setDownstreamMode(DownstreamTheater theater) {
        return new SetDownstreamMode(theater);
    }

    @Bean
    ForceHalfOpenProbe forceHalfOpenProbe(BreakerPort reliabilityBreaker) {
        return new ForceHalfOpenProbe(reliabilityBreaker);
    }

    @Bean
    ResetResilienceDemo resetResilienceDemo(ResilientCaller resilientCaller,
            DownstreamTheater theater, VirtualTime reliabilityVirtualTime,
            ReadBreakerStatus readBreakerStatus) {
        return new ResetResilienceDemo(resilientCaller, theater, reliabilityVirtualTime,
                readBreakerStatus);
    }
}

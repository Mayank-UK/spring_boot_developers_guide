package guide.config;

import guide.content.ContentRegistry;
import guide.content.ContentTopics;
import guide.core.AppConfig;
import guide.domain.best.PracticeChecklist;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Topic 42 (best-practices) wiring — the best:checklist demo's composition
 * root. The pattern is the env inspector's, sharpened: the ONLY Spring-side
 * code in the demo is right here, reading the real {@link Environment} and
 * the typed {@link AppConfig} and folding them into a plain
 * {@link PracticeChecklist.Facts} record the domain grades. Every live
 * verdict therefore reports in-app state that exists before the first
 * request — deterministic by construction, never a clock or a coin.
 *
 * The registry fact is a LOCAL construction, not a bean: the full app's
 * GuideConfig already owns the {@code ContentRegistry} bean, and building a
 * second one here purely to re-prove startup validation keeps the slice and
 * the whole app on one wiring story (no duplicate bean definitions).
 *
 * In a @WebMvcTest slice there is no GuideConfig — this config brings its
 * own AppConfig (bound from the same application.yml on the test classpath)
 * and its own facts, so the demo answers identically in slice and full app.
 */
@Configuration
@EnableConfigurationProperties(AppConfig.class)
public class BestPracticesDemoConfig {

    /** The graded facts, read once from real state at boot. */
    @Bean
    PracticeChecklist.Facts practiceChecklistFacts(AppConfig appConfig, Environment environment) {
        ContentRegistry registry = new ContentRegistry(ContentTopics.all());
        return new PracticeChecklist.Facts(
                appConfig.deployEnvironment(),
                true,
                registry.getTopics().size(),
                true,
                environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false),
                "graceful".equals(environment.getProperty("server.shutdown")),
                environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", "30s"),
                environment.getProperty("management.endpoint.health.probes.enabled", Boolean.class, false),
                environment.getProperty("management.endpoints.web.exposure.include", "")
                        .contains("prometheus"));
    }

    @Bean
    PracticeChecklist practiceChecklist() {
        return new PracticeChecklist();
    }
}

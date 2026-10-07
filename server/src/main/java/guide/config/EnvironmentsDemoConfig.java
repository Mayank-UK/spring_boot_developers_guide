package guide.config;

import guide.core.AppConfig;
import guide.core.scenario.ScenarioStore;
import guide.domain.environments.ActiveProfiles;
import guide.domain.environments.InspectEnvironment;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Topic 36 (environments) wiring — the env inspector's composition root.
 * This is where the topic's central pattern becomes real: {@link AppConfig}
 * is registered with {@code @EnableConfigurationProperties}, so Boot binds
 * the {@code guide.env.*} property sources into ONE typed record — the
 * constructor validates at startup, and everything downstream (the use case,
 * the controller) reads typed accessors instead of string lookups.
 *
 * The {@link ScenarioStore} is injected, never duplicated: the full app
 * binds GuideConfig's store, and slice tests supply their own through a
 * {@code @TestConfiguration} (the testing demo's established pattern).
 */
@Configuration
@EnableConfigurationProperties(AppConfig.class)
public class EnvironmentsDemoConfig {

    /**
     * The active profiles, read once from the real Environment at the
     * composition root — the only place allowed to know Spring — and handed
     * to the domain as a plain value. Empty means Spring's "default".
     */
    @Bean
    ActiveProfiles activeProfiles(Environment environment) {
        return new ActiveProfiles(List.of(environment.getActiveProfiles()));
    }

    @Bean
    InspectEnvironment inspectEnvironment(AppConfig appConfig, ActiveProfiles activeProfiles,
            ScenarioStore scenarioStore) {
        return new InspectEnvironment(appConfig, activeProfiles, scenarioStore);
    }
}

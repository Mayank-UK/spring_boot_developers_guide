package guide.config;

import guide.domain.deployment.InspectDeployment;
import guide.domain.deployment.ShutdownPosture;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Topic 37 (deployment) wiring — the deployment-story demo's composition
 * root. The one Spring-aware act is reading the graceful-shutdown posture
 * from the real {@link Environment} ONCE, at boot: the use case and the
 * controller downstream only ever see the plain domain record, exactly like
 * the environments topic's ActiveProfiles pattern.
 *
 * The typed ComposeStack mirror carries no Spring knowledge at all — it is
 * a static inventory the use case renders, deterministic by construction.
 */
@Configuration
public class DeployDemoConfig {

    /**
     * The graceful-shutdown posture, read from the property sources Boot
     * actually bound (application.yml sets both keys for topic 37): mode
     * from server.shutdown, the per-phase budget from
     * spring.lifecycle.timeout-per-shutdown-phase.
     */
    @Bean
    ShutdownPosture shutdownPosture(Environment environment) {
        return new ShutdownPosture(
                environment.getProperty("server.shutdown", "immediate"),
                environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", "30s"));
    }

    @Bean
    InspectDeployment inspectDeployment(ShutdownPosture shutdownPosture) {
        return new InspectDeployment(shutdownPosture);
    }
}

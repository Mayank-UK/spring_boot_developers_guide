package guide.config;

import guide.domain.scaling.ExternalCounterStore;
import guide.domain.scaling.InstancePersona;
import guide.domain.scaling.ProbeStatelessness;
import guide.domain.scaling.ProcessIdentity;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 40 (scaling) wiring — the scaling:stateless demo's composition
 * root. The arrangement is the entire lesson in one bean method: TWO
 * personas (the simulated second replica) sharing ONE external store,
 * plus the real process identity so the report can say which JVM is
 * actually talking. Everything downstream is framework-free domain code;
 * the demo stays deterministic with no running redis — the shared store
 * is the in-process stand-in (its javadoc owns that honesty), and the
 * statelessness it proves is about WHERE the value lives, not which
 * process holds it.
 */
@Configuration
public class ScalingDemoConfig {

    @Bean
    ProbeStatelessness probeStatelessness() {
        return new ProbeStatelessness(
                List.of(new InstancePersona("server-a"), new InstancePersona("server-b")),
                new ExternalCounterStore(),
                ProcessIdentity.current());
    }
}

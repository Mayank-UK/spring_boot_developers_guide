package guide.config;

import guide.data.core.container.BeansAdapter;
import guide.domain.core.container.ContainerSnapshotSource;
import guide.domain.core.container.InspectBean;
import guide.domain.core.container.LayeredUseCases;
import guide.domain.core.container.ShowContainer;
import guide.domain.core.container.SuffixedUseCases;
import guide.domain.core.container.UseCaseConvention;
import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 05 (spring-core) demo wiring — the container demoing the container,
 * through the same port→adapter seam every topic uses. BeansAdapter is the
 * only Spring-aware piece (it reads the ConfigurableApplicationContext
 * directly); the exhibit and use cases stay domain-pure.
 *
 * The UseCaseConvention pair below is the topic's REAL example of two beans
 * implementing one interface: ShowContainer receives them as a List —
 * collection injection — so the conventions the demo honors are exactly the
 * beans the context holds. One @Configuration per topic keeps the composition
 * root growing in reviewable units.
 */
@Configuration
public class CoreDemoConfig {

    @Bean
    ContainerSnapshotSource containerSnapshotSource(ConfigurableApplicationContext context) {
        return new BeansAdapter(context);
    }

    // ---- two beans, one interface: the wiring-ambiguity exhibit ----

    @Bean
    UseCaseConvention layeredUseCaseConvention() {
        return new LayeredUseCases();
    }

    @Bean
    UseCaseConvention suffixedUseCaseConvention() {
        return new SuffixedUseCases();
    }

    @Bean
    ShowContainer showContainer(ContainerSnapshotSource containerSnapshotSource,
            List<UseCaseConvention> useCaseConventions) {
        return new ShowContainer(containerSnapshotSource, useCaseConventions);
    }

    @Bean
    InspectBean inspectBean(ContainerSnapshotSource containerSnapshotSource) {
        return new InspectBean(containerSnapshotSource);
    }
}

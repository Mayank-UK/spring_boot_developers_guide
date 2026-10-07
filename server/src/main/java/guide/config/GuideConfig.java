package guide.config;

import guide.content.ContentRegistry;
import guide.content.ContentTopics;
import guide.core.logging.RequestIdFilter;
import guide.core.scenario.ScenarioState;
import guide.core.scenario.ScenarioStore;
import guide.data.content.ContentRepositoryAdapter;
import guide.domain.content.ContentRepository;
import guide.domain.content.GetTopicById;
import guide.domain.content.ListTopicSummaries;
import guide.domain.java.InspectRuntimeType;
import guide.domain.java.RuntimeTypesExhibit;
import guide.domain.java.ShowErasureEvidence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The composition root: every port→adapter and every use case is an explicit
 * bean here, wired by constructor injection. Nothing below presentation
 * knows Spring; this file is where Spring meets the domain.
 */
@Configuration
public class GuideConfig {

    @Bean
    ContentRegistry contentRegistry() {
        return new ContentRegistry(ContentTopics.all());
    }

    @Bean
    ContentRepository contentRepository(ContentRegistry registry) {
        return new ContentRepositoryAdapter(registry);
    }

    @Bean
    ListTopicSummaries listTopicSummaries(ContentRepository repository) {
        return new ListTopicSummaries(repository);
    }

    @Bean
    GetTopicById getTopicById(ContentRepository repository) {
        return new GetTopicById(repository);
    }

    // ---- topic 01 (java-language) demo: reflection + erasure evidence ----

    @Bean
    RuntimeTypesExhibit runtimeTypesExhibit() {
        return new RuntimeTypesExhibit();
    }

    @Bean
    InspectRuntimeType inspectRuntimeType(RuntimeTypesExhibit runtimeTypesExhibit) {
        return new InspectRuntimeType(runtimeTypesExhibit);
    }

    @Bean
    ShowErasureEvidence showErasureEvidence(RuntimeTypesExhibit runtimeTypesExhibit) {
        return new ShowErasureEvidence(runtimeTypesExhibit);
    }

    /**
     * Initial state read from the real environment (SCENARIO_DATA_MODE /
     * SCENARIO_INJECT_FAILURE, set by shell or Docker env), validated at
     * startup. Demo endpoints then flip it at runtime.
     */
    @Bean
    ScenarioStore scenarioStore(Environment environment) {
        return new ScenarioStore(ScenarioState.fromEnvironment(
                environment.getProperty("SCENARIO_DATA_MODE"),
                environment.getProperty("SCENARIO_INJECT_FAILURE")));
    }

    /** Registered by Boot as a servlet filter; tests and reset paths autowire the store above. */
    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }
}

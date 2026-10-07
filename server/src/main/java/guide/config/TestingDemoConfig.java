package guide.config;

import guide.core.scenario.ScenarioStore;
import guide.data.posts.InMemoryPostRepository;
import guide.data.testing.RestClientWebProbe;
import guide.domain.posts.GetPost;
import guide.domain.posts.ListPosts;
import guide.domain.testing.RunTestBattery;
import guide.domain.testing.WebProbe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Topic 34 (testing) wiring — the runner demo's composition root. The
 * battery exercises whatever the HOSTING context provides:
 *
 * - the full app lends its real posts use cases (scenario-routed, fake by
 *   default) and its live web server, so the web check performs a genuine
 *   self-call;
 * - a MockMvc slice that imports only this config gets the fake posts
 *   substrate (the deterministic twelve-post seed) and NO live server —
 *   the web check then reports SKIPPED, the same honesty the traced flow's
 *   probe shows in slices;
 * - the {@link ScenarioStore} is injected, never duplicated: the full app
 *   binds GuideConfig's store, and slice tests supply their own through a
 *   {@code @TestConfiguration} (the redis demo's established pattern), so
 *   the injected-failure exhibit flips through the SAME store the demo
 *   reads.
 */
@Configuration
public class TestingDemoConfig {

    @Bean
    WebProbe webProbe(ObjectProvider<RestClient.Builder> clientBuilder,
            ObjectProvider<WebServerApplicationContext> serverContext) {
        return new RestClientWebProbe(clientBuilder.getIfAvailable(), serverContext.getIfAvailable());
    }

    @Bean
    RunTestBattery runTestBattery(ObjectProvider<ListPosts> listPosts, ObjectProvider<GetPost> getPost,
            WebProbe webProbe, ScenarioStore scenarioStore) {
        InMemoryPostRepository substrate = new InMemoryPostRepository();
        return new RunTestBattery(
                listPosts.getIfAvailable(() -> new ListPosts(substrate)),
                getPost.getIfAvailable(() -> new GetPost(substrate)),
                webProbe,
                scenarioStore);
    }
}

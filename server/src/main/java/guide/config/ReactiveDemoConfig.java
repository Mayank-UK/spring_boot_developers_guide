package guide.config;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.reactive.WebClientSelfFetch;
import guide.domain.reactive.CompareExecutionModels;
import guide.domain.reactive.DemonstrateBackpressure;
import guide.domain.reactive.ReactiveSelfFetch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Topic 41 (reactive-spring) wiring — the reactive:compare demo's
 * composition root. The two engines are framework-free domain beans; the
 * one Spring-side decision worth teaching is the CLIENT HALF: the
 * {@link ReactiveSelfFetch} port gets TWO WebClients — a STUB whose
 * transport is {@link WebClientSelfFetch#stubExchange} (deterministic,
 * portless — what the MockMvc slice and the default fake mode run) and a
 * LIVE one over reactor-netty against this app's own listening port — and
 * the ScenarioStore's per-area mode decides AT REQUEST TIME which answers.
 * That is the app's standing fake/live invariant: no demo needs the
 * network to be teachable, and every demo can go live without a rebuild.
 *
 * In a @WebMvcTest slice there is no ScenarioStore bean — the
 * ObjectProvider comes back empty and the demo stays on the stub. In the
 * running app, PATCH /api/demo/scenario {"dataMode":"live"} (or the area
 * override) flips the fetch to a real self-call.
 */
@Configuration
public class ReactiveDemoConfig {

    @Bean
    CompareExecutionModels compareExecutionModels() {
        return new CompareExecutionModels();
    }

    @Bean
    DemonstrateBackpressure demonstrateBackpressure() {
        return new DemonstrateBackpressure();
    }

    @Bean
    ReactiveSelfFetch reactiveSelfFetch(Environment environment,
            ObjectProvider<ScenarioStore> scenarioStore) {
        String port = environment.getProperty("server.port", "8080");
        WebClient liveClient = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
        WebClient stubClient = WebClient.builder()
                .exchangeFunction(WebClientSelfFetch::stubExchange)
                .build();
        return new WebClientSelfFetch(liveClient, stubClient,
                () -> {
                    ScenarioStore store = scenarioStore.getIfAvailable();
                    return store != null && store.dataModeFor("reactive") == ScenarioDataMode.LIVE;
                });
    }
}

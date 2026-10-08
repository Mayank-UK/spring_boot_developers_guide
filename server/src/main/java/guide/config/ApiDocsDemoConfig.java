package guide.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Topic 12 (api-documentation) wiring — the demo's two explicit beans:
 *
 * 1. The OpenAPI bean, the ONE springdoc customization this app makes: the
 *    document's info block. Everything else in the generated spec (every
 *    path, parameter, and schema) is derived from the controllers themselves;
 *    this bean only names the API, because a title and a version are the two
 *    things derivation cannot know. It is springdoc's documented extension
 *    point — it merges into the generated document, and the drift test pins
 *    the title so even this metadata cannot silently change.
 *
 * 2. The RestClient the coverage endpoint self-fetches /v3/api-docs through —
 *    wired here (not auto-configured in this app) so the controller depends
 *    on a plain client bean, swappable without touching the edge. The client
 *    is deliberately NOT the classpath-detected default: its read budget is
 *    sized for springdoc's first-generation scan (see apiDocsRestClient).
 */
@Configuration
public class ApiDocsDemoConfig {

    /** The document's identity: title, one-paragraph description, version. */
    @Bean
    OpenAPI guideOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Spring Boot Developer's Guide API")
                .description("The guide server's whole HTTP surface — generated from the "
                        + "controllers at runtime by springdoc-openapi. Interactive docs at "
                        + "/docs; the raw contract at /v3/api-docs.")
                .version("1"));
    }

    /**
     * The self-fetch client for the coverage demo — one localhost hop, with
     * its OWN deliberately generous read budget. The demo's FIRST caller
     * after boot triggers springdoc's on-demand generation, and that first
     * scan walks every controller in the app (44 topics' worth — 16+ seconds
     * measured) before the document's first byte exists; a client on the
     * classpath-detected default (~10s read, Reactor Netty here) mistakes
     * that scan for a dead server and 500s exactly once. 60s states the
     * contract out loud: the report is derived-and-correct, not
     * latency-bound. This bean is the demo's private client — the shared
     * RestClient.Builder other topics teach timeouts on keeps its semantics.
     */
    @Bean
    RestClient apiDocsRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
    }
}

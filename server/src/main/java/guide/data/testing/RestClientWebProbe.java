package guide.data.testing;

import guide.domain.testing.WebProbe;
import java.util.Optional;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * Adapter for the test-runner demo's web check (topic 34): the app calls
 * ITSELF over real HTTP — the same self-call seam the observability topic's
 * trace probe uses — and answers the status the wire returned.
 *
 * Degrades honestly: a context without the autoconfigured client builder or
 * a live web server (a MockMvc slice) answers {@link Optional#empty()} and
 * the battery records a SKIPPED. Transport failures propagate as exceptions
 * so the use case can report them as genuine FAILED checks — an unreachable
 * self is a finding, not a crash.
 */
public final class RestClientWebProbe implements WebProbe {

    /** The endpoint the probe polls: cheap, read-only, always mounted. */
    static final String PROBE_PATH = "/api/demo/scenario";

    private final RestClient.Builder clientBuilder;
    private final WebServerApplicationContext serverContext;

    /** Either collaborator may be null — the probe then reports "no server to call". */
    public RestClientWebProbe(RestClient.Builder clientBuilder, WebServerApplicationContext serverContext) {
        this.clientBuilder = clientBuilder;
        this.serverContext = serverContext;
    }

    @Override
    public Optional<Integer> selfCallScenarioStatus() {
        if (clientBuilder == null || serverContext == null) {
            return Optional.empty();
        }
        int port = serverContext.getWebServer().getPort();
        return Optional.of(clientBuilder.clone().build()
                .get().uri("http://localhost:" + port + PROBE_PATH)
                .retrieve().toBodilessEntity().getStatusCode().value());
    }
}

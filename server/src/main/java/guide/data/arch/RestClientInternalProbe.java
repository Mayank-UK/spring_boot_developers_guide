package guide.data.arch;

import guide.core.logging.RequestIdFilter;
import guide.domain.arch.InternalProbe;
import java.net.URI;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Adapter: answers the InternalProbe port with a REAL HTTP self-call —
 * one RestClient GET against this very server, exactly the hop the
 * terminal companion's curl makes, so the story's evidence is produced by
 * the genuine servlet stack (filters, DispatcherServlet, handler) rather
 * than an in-process shortcut.
 *
 * exchange() rather than retrieve(): an error status from the inner
 * endpoint is DATA for the tracer (the story shows a failed inner request
 * with postHandle and afterCompletion still running), not an exception to
 * raise. The base URI derives from the incoming request, so the demo works
 * on any port; the inner request's correlation id is read from the
 * x-request-id header the RequestIdFilter echoes back. A plain class wired
 * as an explicit @Bean in ArchDemoConfig — composition-root discipline.
 *
 * The RestClient is built HERE, adapter-private, and deliberately NOT a
 * shared @Bean: ApiDocsDemoController (topic 12) injects a RestClient BY
 * TYPE, so every additional bare RestClient bean is an autowiring
 * ambiguity waiting for some future @SpringBootTest to trip over — two
 * candidates, no @Primary, context refuses to load. The client is this
 * adapter's implementation detail, not part of the app's dependency
 * surface; what the composition root chooses between is PORTS. (Topic 12's
 * own client bean stays the one by-type candidate; topic 33's trace probe
 * sidesteps the same trap via the autoconfigured RestClient.Builder.)
 */
public class RestClientInternalProbe implements InternalProbe {

    private final RestClient restClient = RestClient.create();

    @Override
    public InternalCall get(String path) {
        URI base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUri();
        URI target = base.resolve(path);
        long startedAt = System.nanoTime();
        return restClient.get().uri(target).exchange((request, response) -> {
            byte[] body = response.getBody().readAllBytes();
            String requestId = response.getHeaders().getFirst(RequestIdFilter.HEADER);
            return new InternalCall(response.getStatusCode().value(), requestId,
                    body.length, System.nanoTime() - startedAt);
        });
    }
}

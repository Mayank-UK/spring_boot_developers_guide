package guide.data.reactive;

import guide.domain.reactive.ReactiveSelfFetch;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Topic 41's WebClient adapter — the reactive HTTP client as a data-layer
 * adapter behind the {@link ReactiveSelfFetch} port. Three deliberate
 * teaching decisions live in this one class:
 *
 * - THE PIPELINE IS THE WORK: {@code get().retrieve().bodyToFlux(...)} —
 *   a Flux of ndjson elements assembled with the same operators the
 *   domain engine uses, ending in {@code collectList().block(timeout)}.
 *   The block is AT THE EDGE, on the calling (virtual) request thread —
 *   the ONLY place block() is legal in an MVC app. Never inside a
 *   pipeline, never on an event loop.
 * - THE THREADS ARE THE EVIDENCE: {@code doOnNext} records the thread
 *   every element arrived on. Live mode shows {@code reactor-http-nio-*}
 *   (the client's event loops — no servlet thread spent a nanosecond
 *   waiting); fake mode shows the stub's instant answer on the caller.
 * - FAKE/LLIVE WITHOUT A SOCKET: the fake client swaps the transport for
 *   an {@link WebClient.Builder#exchangeFunction} that answers from the
 *   request URL itself — deterministic, portless, and exercised by the
 *   MockMvc slice; the live client uses reactor-netty against this app's
 *   own listening port (ScenarioStore's per-area mode decides at runtime).
 */
public final class WebClientSelfFetch implements ReactiveSelfFetch {

    public static final String STREAM_PATH = "/api/demo/reactive/stream";

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final WebClient liveClient;
    private final WebClient stubClient;
    private final BooleanSupplier liveMode;

    public WebClientSelfFetch(WebClient liveClient, WebClient stubClient, BooleanSupplier liveMode) {
        this.liveClient = liveClient;
        this.stubClient = stubClient;
        this.liveMode = liveMode;
    }

    @Override
    public FetchReport fetch(int items, long delayMillis) {
        boolean live = liveMode.getAsBoolean();
        WebClient client = live ? liveClient : stubClient;
        AtomicLong wall = new AtomicLong();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        long start = System.nanoTime();
        List<StreamItem> fetched = client.get()
                .uri(builder -> builder.path(STREAM_PATH)
                        .queryParam("items", items)
                        .queryParam("delayMs", delayMillis)
                        .build())
                .accept(NDJSON)
                .retrieve()
                .bodyToFlux(StreamItem.class)
                .doOnNext(item -> threads.add(Thread.currentThread().getName()))
                .collectList()
                .block(Duration.ofSeconds(20));
        wall.set(System.nanoTime() - start);
        String url = STREAM_PATH + "?items=" + items + "&delayMs=" + delayMillis;
        List<String> reading = new ArrayList<>();
        reading.add(live
                ? "LIVE mode: a real reactor-netty round trip to this app's own "
                        + STREAM_PATH + " — the reactive client fetched the stream it is served beside."
                : "FAKE mode: the transport is a stubbed exchange function — the same pipeline, "
                        + "no socket. PATCH /api/demo/scenario to {\"dataMode\":\"live\"} for a real round trip.");
        reading.add("every element arrived on " + (live ? "the client's event loops" : "the stubbed exchange's thread")
                + ": " + String.join(", ", threads.stream().sorted().limit(4).toList()) + ".");
        reading.add("the pipeline ends in collectList().block() ON PURPOSE — blocking is legal at the"
                + " edge (this MVC request runs on a virtual thread) and illegal inside a pipeline or on an event loop.");
        return new FetchReport(live ? "live" : "fake", url, items,
                fetched == null ? 0 : fetched.size(),
                threads.stream().sorted().limit(4).toList(),
                wall.get(), List.copyOf(reading));
    }

    /**
     * The fake transport: answer the stream request straight from its URL —
     * same ndjson shape, same content type, zero network. Wired by
     * ReactiveDemoConfig so tests and the default demo mode never open a port.
     */
    public static Mono<ClientResponse> stubExchange(ClientRequest request) {
        URI url = request.url();
        int items = boundedParam(url, "items", 5);
        StringBuilder body = new StringBuilder();
        for (int seq = 1; seq <= items; seq++) {
            if (seq > 1) {
                body.append('\n');
            }
            body.append("{\"seq\":").append(seq).append(",\"payload\":\"item-").append(seq).append("\"}");
        }
        return Mono.just(ClientResponse.create(HttpStatusCode.valueOf(200))
                .header(HttpHeaders.CONTENT_TYPE, NDJSON.toString())
                .body(body.toString())
                .build());
    }

    private static int boundedParam(URI url, String name, int fallback) {
        String raw = rawQueryParameter(url, name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(0, Math.min(Integer.parseInt(raw), 256));
        } catch (NumberFormatException notNumeric) {
            return fallback;
        }
    }

    private static String rawQueryParameter(URI url, String name) {
        String query = url.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}

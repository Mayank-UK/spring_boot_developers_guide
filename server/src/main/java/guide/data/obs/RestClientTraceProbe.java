package guide.data.obs;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.obs.LogRing;
import guide.domain.obs.LoggedEvent;
import guide.domain.obs.SpanTimeline;
import guide.domain.obs.TraceEvidence;
import guide.domain.obs.TraceProbe;
import guide.domain.obs.TraceSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The trace probe's adapter: makes the app call ITSELF over real HTTP with a
 * W3C {@code traceparent} built by hand from the current trace context, then
 * assembles the evidence — the spans that trace produced, and the log lines
 * whose MDC carried the same trace id.
 *
 * Building the header BY HAND is the teaching move: instrumented clients
 * (an autoconfigured {@link RestClient.Builder} is one) inject this header
 * automatically — doing it once, visibly, from {@code traceId}/{@code spanId}
 * shows exactly what that automation writes on the wire. The hop endpoint
 * echoes the header it actually received, so the response PROVES the context
 * crossed the HTTP boundary (if framework instrumentation also stamped the
 * call, the echoed header is its value — same trace, newer parent — and the
 * demo renders both).
 *
 * Degrades honestly, like every optional-service adapter in this repo: a
 * context without the tracer, the instrumented client builder, or a live
 * web server (a MockMvc slice) gets a typed UNAVAILABLE failure instead of
 * a fabricated trace.
 */
public final class RestClientTraceProbe implements TraceProbe {

    /** The self-call's hop path; the controller serves it at the same origin. */
    static final String HOP_PATH = "/api/demo/obs/trace/hop";

    /** The request id the probe stamps on the hop, so its logs are greppable by it. */
    static final String HOP_REQUEST_ID = "obs-trace-hop";

    /** How long to wait (polling) for finished spans to reach the timeline — reporting is async on the hop's thread. */
    private static final long SPAN_SETTLE_MS = 2_000;

    /** How long to wait (polling) for the hop's log beats to reach the ring — appends can trail the
     * response on a cold box, the same async truth the span settle handles. */
    private static final long LOG_SETTLE_MS = 2_000;

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<RestClient.Builder> clientBuilder;
    private final ObjectProvider<WebServerApplicationContext> serverContext;
    private final SpanTimeline spans;
    private final LogRing ring;

    public RestClientTraceProbe(ObjectProvider<Tracer> tracer,
            ObjectProvider<RestClient.Builder> clientBuilder,
            ObjectProvider<WebServerApplicationContext> serverContext,
            SpanTimeline spans,
            LogRing ring) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.clientBuilder = Objects.requireNonNull(clientBuilder, "clientBuilder");
        this.serverContext = Objects.requireNonNull(serverContext, "serverContext");
        this.spans = Objects.requireNonNull(spans, "spans");
        this.ring = Objects.requireNonNull(ring, "ring");
    }

    @Override
    public Result<TraceEvidence> run() {
        Tracer active = tracer.getIfAvailable();
        RestClient.Builder builder = clientBuilder.getIfAvailable();
        WebServerApplicationContext server = serverContext.getIfAvailable();
        if (active == null || builder == null || server == null) {
            return Result.failed(Failure.of(FailureKind.UNAVAILABLE,
                    "the traced flow needs the full runtime — tracer, HTTP client, live web server; "
                            + "this context (a test slice?) has none to cross",
                    null));
        }
        Span current = active.currentSpan();
        if (current == null) {
            return Result.failed(Failure.of(FailureKind.UNAVAILABLE,
                    "no active trace context on this thread — serve /trace over HTTP so the server "
                            + "observation opens one", null));
        }
        String traceId = current.context().traceId();
        String traceparent = "00-" + traceId + "-" + current.context().spanId() + "-01";
        int port = server.getWebServer().getPort();

        HopWire hop;
        try {
            hop = builder.clone().build()
                    .get().uri("http://localhost:" + port + HOP_PATH + "?origin=traced-flow")
                    .header("traceparent", traceparent)
                    .header("x-request-id", HOP_REQUEST_ID)
                    .retrieve().body(HopWire.class);
        } catch (RestClientException transportFailure) {
            return Result.failed(Failure.of(FailureKind.INTERNAL,
                    "the self-call failed: " + transportFailure.getMessage(), null));
        }
        if (hop == null) {
            return Result.failed(Failure.of(FailureKind.INTERNAL,
                    "the hop answered an empty body — nothing to prove propagation with", null));
        }
        if (hop.traceIdFromMdc() == null || !hop.traceIdFromMdc().equals(traceId)) {
            return Result.failed(Failure.of(FailureKind.INTERNAL,
                    "trace propagation broke: the hop saw trace " + hop.traceIdFromMdc()
                            + " while the caller carried " + traceId, null));
        }

        List<TraceSpan> timeline = awaitSettledSpans(traceId);
        awaitSettledTraceLogs(traceId);
        List<LoggedEvent> traceLogs = ring.recent(null, 200).stream()
                .filter(event -> traceId.equals(event.traceId()))
                .toList();
        return Result.success(new TraceEvidence(traceId, traceparent, hop.toProof(), timeline, traceLogs));
    }

    /** Reports are asynchronous (the hop's span finishes on the hop's thread): poll briefly for the waterfall. */
    private List<TraceSpan> awaitSettledSpans(String traceId) {
        List<TraceSpan> settled = spans.recentFor(traceId);
        for (long waited = 0; settled.size() < 2 && waited < SPAN_SETTLE_MS; waited += 50) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            settled = spans.recentFor(traceId);
        }
        return settled;
    }

    /** The hop's log beats can land after its response is already on the wire (the ring appends ride
     * the logging thread): poll briefly for the first trace-tagged line — the same settle-then-snapshot
     * posture as the spans — so the evidence never ships an empty log section to a fast reader. A real
     * propagation break still fails: after the budget the snapshot below stays empty. */
    private void awaitSettledTraceLogs(String traceId) {
        for (long waited = 0; countTraceLogs(traceId) == 0 && waited < LOG_SETTLE_MS; waited += 50) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private long countTraceLogs(String traceId) {
        return ring.recent(null, 200).stream()
                .filter(event -> traceId.equals(event.traceId()))
                .count();
    }

    /** Mirror of the hop endpoint's response (Jackson fills it from the JSON the wire carried). */
    record HopWire(String traceparentSeen, String traceIdFromMdc, HopFactsWire facts) {

        guide.domain.obs.TraceEvidence.HopProof toProof() {
            guide.domain.obs.RunTracedHop.HopFacts proofFacts = facts == null
                    ? new guide.domain.obs.RunTracedHop.HopFacts(null, null, null, null, List.of())
                    : new guide.domain.obs.RunTracedHop.HopFacts(
                            facts.origin(), facts.postId(), facts.jobId(), facts.outcome(), facts.steps());
            return new guide.domain.obs.TraceEvidence.HopProof(traceparentSeen, traceIdFromMdc, proofFacts);
        }
    }

    record HopFactsWire(String origin, String postId, String jobId, String outcome, List<String> steps) {
    }
}

package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.obs.PrometheusExcerpt;
import guide.domain.obs.EmitDemoLog;
import guide.domain.obs.LoggedEvent;
import guide.domain.obs.ObsMetrics;
import guide.domain.obs.ReadLogRing;
import guide.domain.obs.RunTracedHop;
import guide.domain.obs.TraceEvidence;
import guide.domain.obs.TraceProbe;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 33 (observability) — the teaching surface over
 * the real machinery:
 *
 * - GET  /api/demo/obs/logs          → the ring: recent structured events
 *   (timestamp, level, logger, message, requestId, traceId) with an exact
 *   level filter and a server-capped limit
 * - GET  /api/demo/obs/logs/emit     → deliberately emit one event at each
 *   level (?level=trace|debug|info|warn|error) and read it back from the
 *   ring — proof the pipeline carried it
 * - GET  /api/demo/obs/metrics/summary → the curated guide.* meter snapshot
 *   (names + values via the MeterRegistry) plus the same guide_* lines the
 *   prometheus endpoint would expose, when that registry is present
 * - GET  /api/demo/obs/trace         → the one-id proof: run the traced
 *   flow (self-call with a hand-built W3C traceparent), then answer the
 *   trace id, the hop's facts, the span waterfall with timings, and the
 *   log lines whose MDC carried the SAME id
 * - GET  /api/demo/obs/trace/hop     → the cross-layer hop itself (also
 *   curl-able on its own): real post + real job episode + one INFO beat,
 *   plus the traceparent it received and the trace id its thread carried
 *
 * Thin as always: bind, call ONE use case (or the probe port), map Result
 * onto HTTP. The trace echo reads MDC rather than a Tracer so the
 * controller stays free of tracing machinery — the MDC key IS the
 * correlation the whole topic teaches.
 */
@RestController
public class ObsDemoController {

    private final ReadLogRing readLogRing;
    private final EmitDemoLog emitDemoLog;
    private final ObsMetrics metrics;
    private final PrometheusExcerpt prometheus;
    private final RunTracedHop runTracedHop;
    private final TraceProbe traceProbe;

    public ObsDemoController(ReadLogRing readLogRing, EmitDemoLog emitDemoLog, ObsMetrics metrics,
            PrometheusExcerpt prometheus, RunTracedHop runTracedHop, TraceProbe traceProbe) {
        this.readLogRing = readLogRing;
        this.emitDemoLog = emitDemoLog;
        this.metrics = metrics;
        this.prometheus = prometheus;
        this.runTracedHop = runTracedHop;
        this.traceProbe = traceProbe;
    }

    @GetMapping(value = "/api/demo/obs/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    ReadLogRing.LogRingView logs(@RequestParam(required = false) String level,
            @RequestParam(required = false) Integer limit) {
        return readLogRing.execute(level, limit);
    }

    @GetMapping(value = "/api/demo/obs/logs/emit", produces = MediaType.APPLICATION_JSON_VALUE)
    LoggedEvent emit(@RequestParam(required = false) String level) {
        return unwrap(emitDemoLog.execute(level));
    }

    /** The curated meters this app owns, plus the prometheus excerpt when that registry is wired. */
    @GetMapping(value = "/api/demo/obs/metrics/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    MetricsSummary metricsSummary() {
        return new MetricsSummary(metrics.snapshot(), prometheus.lines());
    }

    /** The money shot: one trace id across the hop's spans AND its log lines. */
    @GetMapping(value = "/api/demo/obs/trace", produces = MediaType.APPLICATION_JSON_VALUE)
    TraceEvidence trace() {
        return unwrap(traceProbe.run());
    }

    /** The cross-layer hop; curl-able, and the traced flow's target. */
    @GetMapping(value = "/api/demo/obs/trace/hop", produces = MediaType.APPLICATION_JSON_VALUE)
    HopResponse hop(@RequestParam(defaultValue = "direct curl") String origin,
            @RequestHeader(value = "traceparent", required = false) String traceparent) {
        RunTracedHop.HopFacts facts = runTracedHop.execute(origin);
        return new HopResponse(traceparent, MDC.get("traceId"), facts);
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }

    /** The metrics summary: curated rows plus the exposition lines, when the prometheus registry exists. */
    public record MetricsSummary(java.util.List<ObsMetrics.MeterRow> meters,
            java.util.List<String> prometheusExcerpt) {
    }

    /** The hop's answer: what arrived on the wire, what this thread's MDC carried, what the work did. */
    public record HopResponse(String traceparentSeen, String traceIdFromMdc, RunTracedHop.HopFacts facts) {
    }
}

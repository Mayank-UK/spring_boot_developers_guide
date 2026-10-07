package guide.domain.obs;

import guide.core.result.Result;

/**
 * Port: run the traced flow and collect the evidence. The adapter of record
 * self-calls the app's own hop endpoint over HTTP with a propagated W3C
 * traceparent ({@code data/obs/RestClientTraceProbe}); the port exists so
 * the controller stays thin and the domain stays free of HTTP clients.
 *
 * Fails with a typed UNAVAILABLE Result when the runtime lacks the pieces
 * the flow needs (tracer, instrumented client, live web server) — test
 * slices get the honest typed 503, not a fake trace.
 */
public interface TraceProbe {

    Result<TraceEvidence> run();
}

package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.reliability.CallDownstream;
import guide.domain.reliability.ForceHalfOpenProbe;
import guide.domain.reliability.ReadBreakerStatus;
import guide.domain.reliability.ResetResilienceDemo;
import guide.domain.reliability.ResilientCaller;
import guide.domain.reliability.SetDownstreamMode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 39 (reliability) — the reliability:breaker
 * theater, live and fully deterministic:
 *
 * - GET  /api/demo/reliability/breaker → 200 the breaker's observable
 *   surface: state, config knobs, window metrics, the recent call reports
 *   (newest first), and a reading narrating what the numbers mean.
 * - POST /api/demo/reliability/call → 200 one call through the whole
 *   stack (breaker → retry+timeout → downstream): per-attempt timeline on
 *   the virtual clock, outcome, breaker state before/after.
 * - PATCH /api/demo/reliability/dial?mode=down → 200 the dial's new
 *   position (healthy | flaky | down | slow) or the 400 envelope.
 * - POST /api/demo/reliability/probe → 200 HALF_OPEN (forces the OPEN →
 *   HALF_OPEN transition — the ops rail) or the 400 envelope when there is
 *   nothing to probe.
 * - POST /api/demo/reliability/reset → 200 the fresh status after a full
 *   reset (breaker, dial, clock, memory).
 *
 * Thin as always: raw string parameters, ONE use case per endpoint,
 * Results mapped onto HTTP. No wall-clock waits exist anywhere behind
 * these doors — the demo's clock is virtual by construction.
 */
@RestController
public class ReliabilityDemoController {

    private final ReadBreakerStatus readBreakerStatus;
    private final CallDownstream callDownstream;
    private final SetDownstreamMode setDownstreamMode;
    private final ForceHalfOpenProbe forceHalfOpenProbe;
    private final ResetResilienceDemo resetResilienceDemo;

    public ReliabilityDemoController(ReadBreakerStatus readBreakerStatus,
            CallDownstream callDownstream, SetDownstreamMode setDownstreamMode,
            ForceHalfOpenProbe forceHalfOpenProbe, ResetResilienceDemo resetResilienceDemo) {
        this.readBreakerStatus = readBreakerStatus;
        this.callDownstream = callDownstream;
        this.setDownstreamMode = setDownstreamMode;
        this.forceHalfOpenProbe = forceHalfOpenProbe;
        this.resetResilienceDemo = resetResilienceDemo;
    }

    /** GET /api/demo/reliability/breaker → the observable breaker. */
    @GetMapping(value = "/api/demo/reliability/breaker", produces = MediaType.APPLICATION_JSON_VALUE)
    ReadBreakerStatus.BreakerStatus breaker() {
        return readBreakerStatus.execute();
    }

    /** POST /api/demo/reliability/call → one call through the stack. */
    @PostMapping(value = "/api/demo/reliability/call", produces = MediaType.APPLICATION_JSON_VALUE)
    ResilientCaller.CallReport call() {
        return callDownstream.execute();
    }

    /** PATCH /api/demo/reliability/dial?mode=down → set the theater's dial. */
    @PatchMapping(value = "/api/demo/reliability/dial", produces = MediaType.APPLICATION_JSON_VALUE)
    String dial(@RequestParam(name = "mode", required = false) String mode) {
        return unwrap(setDownstreamMode.execute(mode));
    }

    /** POST /api/demo/reliability/probe → force OPEN → HALF_OPEN. */
    @PostMapping(value = "/api/demo/reliability/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    String probe() {
        return unwrap(forceHalfOpenProbe.execute());
    }

    /** POST /api/demo/reliability/reset → everything back to the anchor state. */
    @PostMapping(value = "/api/demo/reliability/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    ReadBreakerStatus.BreakerStatus reset() {
        return resetResilienceDemo.execute();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

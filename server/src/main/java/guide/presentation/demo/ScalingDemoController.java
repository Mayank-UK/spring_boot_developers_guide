package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.scaling.ProbeStatelessness;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 40 (scaling) — statelessness, measured:
 *
 * - GET /api/demo/scaling/stateless → one request through the simulated
 *   two-replica fleet: the process's real instance identity (pid@host),
 *   the persona that served the request (sticky via ?instance=a|b, else
 *   round-robin), every persona's PRIVATE counter, the SHARED store's
 *   value, what this caller was told, and what a local-memory world would
 *   have forgotten — or the 400 envelope for a bad instance/store.
 * - GET /api/demo/scaling/stateless/reset → zeroed probe (fresh fleet).
 *
 * Thin as always: raw string parameters in, ONE use case call, the Result
 * mapped onto HTTP. The GET mutates on purpose — each call IS one request
 * through the fleet, which is exactly what the demo measures.
 */
@RestController
public class ScalingDemoController {

    private final ProbeStatelessness probeStatelessness;

    public ScalingDemoController(ProbeStatelessness probeStatelessness) {
        this.probeStatelessness = probeStatelessness;
    }

    /** GET /api/demo/scaling/stateless?instance=a&store=local → 200 probe or 400 envelope. */
    @GetMapping(value = "/api/demo/scaling/stateless", produces = MediaType.APPLICATION_JSON_VALUE)
    ProbeStatelessness.Probe probe(
            @RequestParam(name = "instance", required = false) String instance,
            @RequestParam(name = "store", required = false) String store) {
        return unwrap(probeStatelessness.hit(instance, store));
    }

    /** GET /api/demo/scaling/stateless/reset → the fleet at zero, router cursor rewound. */
    @GetMapping(value = "/api/demo/scaling/stateless/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    ProbeStatelessness.Probe reset() {
        return probeStatelessness.reset();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

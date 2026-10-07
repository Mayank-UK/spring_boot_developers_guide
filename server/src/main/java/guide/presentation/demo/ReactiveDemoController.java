package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.reactive.CompareExecutionModels;
import guide.domain.reactive.DemonstrateBackpressure;
import guide.domain.reactive.ReactiveSelfFetch;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Demo endpoints for topic 41 (reactive-spring) — the reactive world
 * served BY the MVC app, never instead of it:
 *
 * - GET /api/demo/reactive/compare → the measured side-by-side: the same
 *   workload on one-virtual-thread-per-item vs a Reactor pipeline with a
 *   flatMap concurrency cap (items, thread shapes, wall time, one shared
 *   checksum — and 400 envelopes for out-of-bounds parameters).
 * - GET /api/demo/reactive/backpressure → the deterministic demand
 *   exhibit: a hot sink against a slow consumer, drop vs buffer, every
 *   count exact.
 * - GET /api/demo/reactive/stream → the WebFlux-STYLE controller method:
 *   it RETURNS a Flux (ndjson), and servlet MVC's async support bridges
 *   the reactive type to the response — the topic's proof that reactive
 *   return types live happily inside an MVC app.
 * - GET /api/demo/reactive/sum → a Mono&lt;Long&gt; over a Flux pipeline:
 *   MVC waits for the single value, then writes one JSON object.
 * - GET /api/demo/reactive/fetch → the WebClient half through the
 *   ReactiveSelfFetch port (fake stub by default, real reactor-netty
 *   self-call in live mode).
 *
 * Thin as always: raw string/int parameters validated at the edge, one
 * use-case call per endpoint, Result-free engines (these demos cannot
 * fail except by bad input, which is the typed 400 envelope).
 */
@RestController
public class ReactiveDemoController {

    private final CompareExecutionModels compareExecutionModels;
    private final DemonstrateBackpressure demonstrateBackpressure;
    private final ReactiveSelfFetch reactiveSelfFetch;

    public ReactiveDemoController(CompareExecutionModels compareExecutionModels,
            DemonstrateBackpressure demonstrateBackpressure,
            ReactiveSelfFetch reactiveSelfFetch) {
        this.compareExecutionModels = compareExecutionModels;
        this.demonstrateBackpressure = demonstrateBackpressure;
        this.reactiveSelfFetch = reactiveSelfFetch;
    }

    /** GET /api/demo/reactive/compare?items=32&concurrency=8 → the measured comparison. */
    @GetMapping(value = "/api/demo/reactive/compare", produces = MediaType.APPLICATION_JSON_VALUE)
    CompareExecutionModels.CompareReport compare(
            @RequestParam(name = "items", defaultValue = "32") int items,
            @RequestParam(name = "concurrency", defaultValue = "8") int concurrency) {
        requireInRange(items, 1, CompareExecutionModels.MAX_ITEMS, "items");
        requireInRange(concurrency, 1, CompareExecutionModels.MAX_CONCURRENCY, "concurrency");
        return compareExecutionModels.compare(items, concurrency);
    }

    /** GET /api/demo/reactive/backpressure?strategy=drop&items=24&initial=4&late=4 → exact counts. */
    @GetMapping(value = "/api/demo/reactive/backpressure", produces = MediaType.APPLICATION_JSON_VALUE)
    DemonstrateBackpressure.BackpressureReport backpressure(
            @RequestParam(name = "strategy", required = false) String strategy,
            @RequestParam(name = "items", defaultValue = "24") int items,
            @RequestParam(name = "initial", defaultValue = "4") int initial,
            @RequestParam(name = "late", defaultValue = "4") int late) {
        String normalized = DemonstrateBackpressure.normalizeStrategy(strategy);
        if (normalized == null) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "strategy must be one of drop, buffer (drop — the shedding policy — is the default)",
                    Map.of("parameter", "strategy", "allowed", "drop, buffer")));
        }
        requireInRange(items, 1, DemonstrateBackpressure.MAX_ITEMS, "items");
        requireInRange(initial, 0, DemonstrateBackpressure.MAX_DEMAND, "initial");
        requireInRange(late, 0, DemonstrateBackpressure.MAX_DEMAND, "late");
        return demonstrateBackpressure.run(normalized, items, initial, late);
    }

    /**
     * GET /api/demo/reactive/stream?items=5&delayMs=0 → application/x-ndjson.
     * A WebFlux-style method served by MVC: ReactiveTypeHandler bridges the
     * Flux to the servlet response (async request, element-per-write), the
     * same machinery StreamingResponseBody and SseEmitter ride.
     */
    @GetMapping(value = "/api/demo/reactive/stream", produces = "application/x-ndjson")
    Flux<ReactiveSelfFetch.StreamItem> stream(
            @RequestParam(name = "items", defaultValue = "5") int items,
            @RequestParam(name = "delayMs", defaultValue = "0") long delayMs) {
        requireInRange(items, 0, 256, "items");
        requireInRange((int) delayMs, 0, 1000, "delayMs");
        Flux<ReactiveSelfFetch.StreamItem> base = Flux.range(1, items)
                .map(seq -> new ReactiveSelfFetch.StreamItem(seq, "item-" + seq));
        return delayMs > 0 ? base.delayElements(Duration.ofMillis(delayMs)) : base;
    }

    /** GET /api/demo/reactive/sum?items=32 → a Mono over a Flux pipeline, one JSON answer. */
    @GetMapping(value = "/api/demo/reactive/sum", produces = MediaType.APPLICATION_JSON_VALUE)
    Mono<Checksum> sum(@RequestParam(name = "items", defaultValue = "32") int items) {
        requireInRange(items, 1, CompareExecutionModels.MAX_ITEMS, "items");
        return Flux.range(1, items)
                .map(CompareExecutionModels::work)
                .reduce(0L, Long::sum)
                .map(total -> new Checksum(items, total));
    }

    /** GET /api/demo/reactive/fetch?items=5&delayMs=0 → the WebClient fetch through the port. */
    @GetMapping(value = "/api/demo/reactive/fetch", produces = MediaType.APPLICATION_JSON_VALUE)
    ReactiveSelfFetch.FetchReport fetch(
            @RequestParam(name = "items", defaultValue = "5") int items,
            @RequestParam(name = "delayMs", defaultValue = "0") long delayMs) {
        requireInRange(items, 1, 256, "items");
        requireInRange((int) delayMs, 0, 1000, "delayMs");
        return reactiveSelfFetch.fetch(items, delayMs);
    }

    /** The /sum answer: how many items, and the checksum the pipelines share. */
    record Checksum(int items, long checksum) {
    }

    private static void requireInRange(int value, int min, int max, String parameter) {
        if (value < min || value > max) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    parameter + " must be between " + min + " and " + max,
                    Map.of("parameter", parameter, "min", min, "max", max)));
        }
    }
}

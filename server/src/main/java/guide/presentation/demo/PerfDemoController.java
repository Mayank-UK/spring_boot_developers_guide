package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.performance.RunCompressionBenchmark;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for topic 38 (performance) — the compression benchmark,
 * live:
 *
 * - GET /api/demo/perf/bench?payload=medium&passes=48 → 200 the benchmark
 *   report: the identity payload size, per-level gzip results (bytes
 *   before/after, ratio, median compress + round-trip micros), the
 *   process's effective server.compression posture, and a reading — or
 *   the 400 envelope for a bad payload/passes.
 *
 * Thin as always: parameters arrive as raw strings, ONE use case call, the
 * Result maps onto HTTP. Total runtime stays well under two seconds — the
 * demo measures, it does not load.
 */
@RestController
public class PerfDemoController {

    private final RunCompressionBenchmark runCompressionBenchmark;

    public PerfDemoController(RunCompressionBenchmark runCompressionBenchmark) {
        this.runCompressionBenchmark = runCompressionBenchmark;
    }

    /** GET /api/demo/perf/bench?payload=medium&passes=48 → 200 report or 400 envelope. */
    @GetMapping(value = "/api/demo/perf/bench", produces = MediaType.APPLICATION_JSON_VALUE)
    RunCompressionBenchmark.BenchReport bench(
            @RequestParam(name = "payload", required = false) String payload,
            @RequestParam(name = "passes", required = false) String passes) {
        return unwrap(runCompressionBenchmark.execute(payload, passes));
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

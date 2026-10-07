package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.jvm.CompareThreadModels;
import guide.domain.jvm.RunStreamingDemo;
import guide.domain.jvm.RunThreadsDemo;
import guide.domain.jvm.StreamingExhibit;
import guide.domain.jvm.ThreadsExhibit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 02 (jvm) — the live half of the threads and
 * streaming sections:
 * - GET /api/demo/jvm/threads?tasks=24&mode=platform|virtual → 200 one run's
 *   measurements, or the 400 envelope for bad tasks/mode
 * - GET /api/demo/jvm/threads/compare?tasks=24 → 200 both models on the same
 *   task count
 * - GET /api/demo/jvm/streaming?chunks=16&charset=utf-8|utf-16 → 200 the
 *   four-consumer report, or the 400 envelope for bad chunks/charset
 *
 * Thin like every controller: parameters arrive as raw strings and flow
 * through ONE use case each; the Result maps onto HTTP. Defaults and
 * validation live in the use cases, not here.
 */
@RestController
public class JvmDemoController {

    private final RunThreadsDemo runThreadsDemo;
    private final CompareThreadModels compareThreadModels;
    private final RunStreamingDemo runStreamingDemo;

    public JvmDemoController(RunThreadsDemo runThreadsDemo, CompareThreadModels compareThreadModels,
            RunStreamingDemo runStreamingDemo) {
        this.runThreadsDemo = runThreadsDemo;
        this.compareThreadModels = compareThreadModels;
        this.runStreamingDemo = runStreamingDemo;
    }

    /** GET /api/demo/jvm/threads?tasks=24&mode=virtual → 200 run report or 400 envelope. */
    @GetMapping("/api/demo/jvm/threads")
    ThreadsExhibit.RunReport threads(@RequestParam(name = "tasks", required = false) String tasks,
            @RequestParam(name = "mode", required = false) String mode) {
        return unwrap(runThreadsDemo.execute(tasks, mode));
    }

    /** GET /api/demo/jvm/threads/compare?tasks=24 → both models on the same tasks. */
    @GetMapping("/api/demo/jvm/threads/compare")
    ThreadsExhibit.ComparisonReport compare(
            @RequestParam(name = "tasks", required = false) String tasks) {
        return unwrap(compareThreadModels.execute(tasks));
    }

    /** GET /api/demo/jvm/streaming?chunks=16&charset=utf-8 → four consumers, one verdict. */
    @GetMapping("/api/demo/jvm/streaming")
    StreamingExhibit.StreamingReport streaming(
            @RequestParam(name = "chunks", required = false) String chunks,
            @RequestParam(name = "charset", required = false) String charset) {
        return unwrap(runStreamingDemo.execute(chunks, charset));
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

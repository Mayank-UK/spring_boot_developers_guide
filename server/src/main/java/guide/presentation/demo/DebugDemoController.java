package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.debug.StallReport;
import guide.domain.debug.StartStall;
import guide.domain.debug.StopStall;
import guide.domain.debug.ReadStallThreads;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 35 (debugging) — the thread-dump story, live:
 *
 * - POST /api/demo/debug/stall/start?kind=lock|spin → park the stall
 *   deterministically (the call RETURNS only once the victim is BLOCKED /
 *   the spinner RUNNABLE) and answer the first snapshot
 * - POST /api/demo/debug/stall/stop       → wake, join, and clear the
 *   stall (idempotent); the answer reports how long it was parked
 * - GET  /api/demo/debug/stall/threads    → the in-process snapshot:
 *   each parked thread's name, state, lock + owner, and stack — the shape
 *   jstack prints — plus the reading lines that narrate what the states
 *   mean, and the JVM's live thread count for honest scale
 *
 * Thin as always: bind, call ONE use case, map Result onto HTTP. Starting
 * while parked is a typed 409 (conflict), an unknown kind a typed 400
 * (validation_failed) — the same envelope as every other boundary here.
 */
@RestController
public class DebugDemoController {

    private final StartStall startStall;
    private final StopStall stopStall;
    private final ReadStallThreads readStallThreads;

    public DebugDemoController(StartStall startStall, StopStall stopStall, ReadStallThreads readStallThreads) {
        this.startStall = startStall;
        this.stopStall = stopStall;
        this.readStallThreads = readStallThreads;
    }

    @PostMapping(value = "/api/demo/debug/stall/start", produces = MediaType.APPLICATION_JSON_VALUE)
    StallReport start(@RequestParam(defaultValue = "lock") String kind) {
        return unwrap(startStall.execute(kind));
    }

    @PostMapping(value = "/api/demo/debug/stall/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    StallReport stop() {
        return unwrap(stopStall.execute());
    }

    @GetMapping(value = "/api/demo/debug/stall/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    StallReport threads() {
        return readStallThreads.execute();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

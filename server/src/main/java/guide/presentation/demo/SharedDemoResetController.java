package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.reset.ResetDemos;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SHARED demo reset — POST /api/demo/reset, one call that returns
 * every mutable demo to its constructed state (guide infrastructure, not a
 * curriculum topic). A reader following several tours accumulates server-
 * side state across requests; before this endpoint each area's reset (or
 * lack of one) was its own adventure.
 *
 * - POST /api/demo/reset              → reset EVERY registered area
 * - POST /api/demo/reset?areas=jobs,messaging → only the named areas;
 *   an unknown area is the typed 400 envelope with the KNOWN list in the
 *   message (derived from the registrations — never a second copy here)
 *
 * Answer: {reset: [{area, reset: true|false, note?}]} — one row per area,
 * sorted by area id. A row with reset=false carries the reason in its
 * note (live infrastructure unreachable is the expected case); the sweep
 * itself never aborts on one area's failure.
 *
 * Thin as always: parse the filter at the edge, ONE use case call, map
 * the Result onto HTTP.
 */
@RestController
public class SharedDemoResetController {

    private final ResetDemos resetDemos;

    public SharedDemoResetController(ResetDemos resetDemos) {
        this.resetDemos = resetDemos;
    }

    /** POST /api/demo/reset?areas=messaging,jobs — empty filter = all areas. */
    @PostMapping(value = "/api/demo/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    ResetDemos.ResetReport reset(@RequestParam(name = "areas", required = false) String areas) {
        return unwrap(resetDemos.execute(parseAreas(areas)));
    }

    /**
     * "messaging, jobs" → ["messaging", "jobs"]; blanks drop out, an
     * all-blank value means "no filter" (same as absent).
     */
    private static List<String> parseAreas(String areas) {
        if (areas == null || areas.isBlank()) {
            return List.of();
        }
        return Arrays.stream(areas.split(","))
                .map(String::trim)
                .filter(area -> !area.isEmpty())
                .toList();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

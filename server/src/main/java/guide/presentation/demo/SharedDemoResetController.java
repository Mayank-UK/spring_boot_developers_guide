package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.ErrorResponse;
import guide.core.logging.RequestIdFilter;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.reset.ResetDemos;
import guide.domain.security.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * A STATE-WIPE NEEDS A GUARD EVEN IN A DEMO: the route sits behind the
 * app-wide /api/** permitAll posture, so ANY anonymous caller can erase
 * every demo's state in one call — the rate limit is the whole defense,
 * and it runs BEFORE the use case, exactly where the security topic
 * teaches a guard belongs. The machinery is the topic-25 RateLimitService
 * every other consumer uses; the refusal is rendered the same way the
 * security lab renders its 429 (same envelope, same Retry-After).
 *
 * Thin as always: the budget check and the filter parse at the edge, ONE
 * use case call, map the Result onto HTTP.
 */
@RestController
public class SharedDemoResetController {

    /** One bucket per CLIENT, keyed by remote address: "demo-reset:127.0.0.1". */
    public static final String RATE_KEY_PREFIX = "demo-reset:";

    /**
     * Burst 5, window 50s: the service expresses refill as capacity-per-window
     * (Bandwidth.classic(5, Refill.greedy(5, 50s))), which works out to
     * exactly one token per 10 seconds sustained — enough for a reader
     * following tours, too few to hammer the wipe-all route.
     */
    public static final long RATE_LIMIT = 5;

    public static final Duration RATE_WINDOW = Duration.ofSeconds(50);

    private final ResetDemos resetDemos;
    private final RateLimitService rateLimitService;

    public SharedDemoResetController(ResetDemos resetDemos, RateLimitService rateLimitService) {
        this.resetDemos = resetDemos;
        this.rateLimitService = rateLimitService;
    }

    /** POST /api/demo/reset?areas=messaging,jobs — empty filter = all areas. */
    @PostMapping(value = "/api/demo/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> reset(@RequestParam(name = "areas", required = false) String areas,
            HttpServletRequest request) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                RATE_KEY_PREFIX + request.getRemoteAddr(), RATE_LIMIT, RATE_WINDOW);
        if (!consumption.allowed()) {
            return refused(consumption, request);
        }
        return ResponseEntity.ok(unwrap(resetDemos.execute(parseAreas(areas))));
    }

    /**
     * The refusal, rendered exactly the way the security lab renders its
     * 429: the typed envelope with the budget's numbers in the details map,
     * and Retry-After the honored caller contract.
     */
    private static ResponseEntity<ErrorResponse> refused(RateLimitService.Consumption consumption,
            HttpServletRequest request) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(consumption.retryAfterSeconds()))
                .body(ErrorResponse.of("rate_limited",
                        "rate budget exhausted for the shared demo reset — retry in "
                                + consumption.retryAfterSeconds() + "s (the bucket refills at "
                                + RATE_LIMIT + " per " + RATE_WINDOW.toSeconds() + "s; a state-wipe "
                                + "needs a guard even in a demo)",
                        requestId(request),
                        Map.of("limit", RATE_LIMIT, "windowSeconds", RATE_WINDOW.toSeconds(),
                                "retryAfterSeconds", consumption.retryAfterSeconds())));
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

    /** The request id the logging filter stamped, or "n/a" — same fallback the security lab's 429 uses. */
    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attribute instanceof String value && !value.isEmpty() ? value : "n/a";
    }
}

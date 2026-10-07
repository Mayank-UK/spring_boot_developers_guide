package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.routing.ExplainRouteMatch;
import guide.domain.routing.ListRoutes;
import guide.domain.routing.RouteTableExhibit;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 07 (routing) — the route explorer plus three REAL
 * pattern-specimen routes, mounted the way every controller in a slice of the
 * API should be: one class-level @RequestMapping base path, method-level
 * mappings relative to it (the mounted-router pattern this topic teaches).
 *
 * - GET /api/demo/routing/table?prefix=/api/demo   → the app's real route
 *   table, read live from Spring's registry, optionally prefix-filtered
 * - GET /api/demo/routing/match?path=/api/content/java-language&method=GET
 *   → which registered mapping wins, which others also matched, and why
 *
 * The specimens (part of the teaching surface, quoted in the topic):
 * - {id:\d+}        a regex-constrained variable — /specimens/42 yes, /specimens/nope never
 * - {section}       a plain variable — overlaps the exact /table and /match routes
 * - {*rest}         a rest-of-path catch-all — swallows every segment after /specimens
 *
 * Thin like every controller: turn the request into plain data, call ONE use
 * case, unwrap the Result. The mapping annotations ARE the routing content.
 */
@RestController
@RequestMapping(RoutingDemoController.BASE)
public class RoutingDemoController {

    /** The mount point: every mapping below is relative to this base. */
    public static final String BASE = "/api/demo/routing";

    private static final String NUMERIC_ID_SPECIMEN = "/specimens/{id:\\d+}";
    private static final String SECTION_SPECIMEN = "/{section}";
    private static final String REST_SPECIMEN = "/specimens/{*rest}";

    private final ListRoutes listRoutes;
    private final ExplainRouteMatch explainRouteMatch;

    public RoutingDemoController(ListRoutes listRoutes, ExplainRouteMatch explainRouteMatch) {
        this.listRoutes = listRoutes;
        this.explainRouteMatch = explainRouteMatch;
    }

    /** GET /api/demo/routing/table?prefix=/api/demo → 200 { prefix, total, routes } or 400. */
    @GetMapping(value = "/table", produces = MediaType.APPLICATION_JSON_VALUE)
    RouteTableExhibit.TableView table(@RequestParam(name = "prefix", required = false) String prefix) {
        return unwrap(listRoutes.execute(prefix));
    }

    /** GET /api/demo/routing/match?path=/api/content/java-language&method=GET → the ranked verdict. */
    @GetMapping(value = "/match", produces = MediaType.APPLICATION_JSON_VALUE)
    RouteTableExhibit.MatchReport match(@RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "method", required = false) String method) {
        return unwrap(explainRouteMatch.execute(method, path));
    }

    /**
     * Specimen 1 — regex-constrained variable. The registry holds
     * /api/demo/routing/specimens/{id:\d+}: only an all-digit segment reaches
     * this handler; /specimens/nope is NOT a 404 from here — the catch-all
     * specimen below swallows it (try the match endpoint to watch the ranking).
     */
    @GetMapping(value = NUMERIC_ID_SPECIMEN, produces = MediaType.APPLICATION_JSON_VALUE)
    RouteTableExhibit.SpecimenReport numericIdSpecimen(@PathVariable String id) {
        return RouteTableExhibit.SpecimenReport.of(BASE + NUMERIC_ID_SPECIMEN, Map.of("id", id),
                "reached the regex specimen: {id:\\d+} bound only because '" + id + "' is all digits");
    }

    /**
     * Specimen 2 — plain variable overlapping exact routes. /table and /match
     * are exact literals, so they outrank this pattern for those paths; every
     * other single segment lands here. GET /api/demo/routing/anything proves it.
     */
    @GetMapping(value = SECTION_SPECIMEN, produces = MediaType.APPLICATION_JSON_VALUE)
    RouteTableExhibit.SpecimenReport sectionSpecimen(@PathVariable String section) {
        return RouteTableExhibit.SpecimenReport.of(BASE + SECTION_SPECIMEN, Map.of("section", section),
                "reached the variable specimen: {section} lost to the exact /table and /match"
                        + " mappings, then beat the catch-all for everything else");
    }

    /** Specimen 3 — rest-of-path capture: everything after /specimens, slashes included. */
    @GetMapping(value = REST_SPECIMEN, produces = MediaType.APPLICATION_JSON_VALUE)
    RouteTableExhibit.SpecimenReport restSpecimen(@PathVariable String rest) {
        return RouteTableExhibit.SpecimenReport.of(BASE + REST_SPECIMEN, Map.of("rest", rest),
                "reached the catch-all specimen: {*rest} bound '" + rest
                        + "' — zero or more segments, captured with leading slash");
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

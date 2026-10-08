package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.ErrorResponse;
import guide.core.errors.Failure;
import guide.core.errors.UnavailableException;
import guide.core.errors.ValidationException;
import guide.core.logging.RequestIdFilter;
import guide.core.result.Failed;
import guide.core.result.Success;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.mongo.ScenarioRoutingVisitStore;
import guide.domain.mongo.ReadRecentVisits;
import guide.domain.mongo.ReadVisitStats;
import guide.domain.mongo.RecordVisit;
import guide.domain.mongo.VisitDocument;
import guide.domain.mongo.VisitDraft;
import guide.domain.mongo.VisitStats;
import guide.domain.mongo.VisitStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 19 (mongodb) — the visits console:
 *
 * - POST /api/demo/mongo/visits         → record one page view into the
 *   CURRENT mode's store (fake's bounded memory or the live collection).
 * - GET  /api/demo/mongo/visits/recent  → newest visits first.
 * - GET  /api/demo/mongo/stats?topicId= → the aggregates: totals, top
 *   topics, average duration, the locale histogram.
 * - GET  /api/demo/mongo/explain        → the aggregation pipelines the
 *   live store builds, rendered as the BSON the server would run — the
 *   transparency artifact, available in both modes.
 * - POST /api/demo/mongo/mode           → flip this area fake/live; a live
 *   request while MongoDB is unreachable answers 200 with the area still
 *   fake plus an honest hint (the optional-service discipline, observable).
 *
 * Thin as every demo edge: parse, judge, call the use case, map the Result;
 * typed exceptions from the live adapter (unavailable) travel to the
 * global advice like any other failure. injectFailure guards every
 * endpoint first, the same ScenarioStore exhibit as the posts console.
 */
@Tag(name = "Mongo", description = "The mongodb topic's demo: the page-visit analytics console "
        + "over a document store — record, aggregate, explain, and flip the area live.")
@RestController
@RequestMapping("/api/demo/mongo")
public class MongoDemoController {

    /** The response carries the area's mode so the console can SHOW which store answered. */
    record VisitsView(String area, String dataMode, VisitDocument visit) {
    }

    record RecentView(String area, String dataMode, List<VisitDocument> visits) {
    }

    record StatsView(String area, String dataMode, long totalVisits, long topicVisits,
            boolean filteredByTopic, long avgDurationMs,
            List<guide.domain.mongo.TopicTally> topTopics,
            List<guide.domain.mongo.LocaleTally> localeHistogram) {
    }

    record ExplainView(String area, String dataMode, String note, List<Document> pipelines) {
    }

    record ModeView(String area, String dataMode, String requested, String database, String hint) {
    }

    record VisitRequest(String topicId, String path, String visitorToken,
            Long durationMs, String locale) {
    }

    record ModeRequest(String dataMode) {
    }

    private final RecordVisit recordVisit;
    private final ReadRecentVisits readRecentVisits;
    private final ReadVisitStats readVisitStats;
    private final ScenarioRoutingVisitStore visitStore;
    private final ScenarioStore scenarioStore;

    public MongoDemoController(RecordVisit recordVisit, ReadRecentVisits readRecentVisits,
            ReadVisitStats readVisitStats, ScenarioRoutingVisitStore visitStore,
            ScenarioStore scenarioStore) {
        this.recordVisit = recordVisit;
        this.readRecentVisits = readRecentVisits;
        this.readVisitStats = readVisitStats;
        this.visitStore = visitStore;
        this.scenarioStore = scenarioStore;
    }

    @Operation(summary = "Record one page visit", operationId = "recordVisit",
            description = "Writes one visit into the current mode's store. visitorToken is "
                    + "optional (default \"anonymous\") and is stored only as a sha-256 hash; "
                    + "locale defaults to \"en\". Malformed bodies answer the typed 422 naming "
                    + "every offending field at once.")
    @PostMapping(value = "/visits", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> visits(@RequestBody VisitRequest body, HttpServletRequest request) {
        requireAvailable();
        return switch (VisitDraft.of(
                body == null ? null : body.topicId(),
                body == null ? null : body.path(),
                body == null ? null : body.visitorToken(),
                body == null ? null : body.durationMs(),
                body == null ? null : body.locale())) {
            case Success<VisitDraft> ok -> switch (recordVisit.execute(ok.value())) {
                case Success<VisitDocument> success -> ResponseEntity.ok(new VisitsView(
                        VisitStore.AREA, dataMode(), success.value()));
                case Failed<VisitDocument> failed -> throw AppException.of(failed.failure());
            };
            case Failed<VisitDraft> failed -> unprocessable(failed.failure(), request);
        };
    }

    @Operation(summary = "The newest visits", operationId = "recentVisits",
            description = "Newest first, at most limit (default 8, hard ceiling 100).")
    @GetMapping(value = "/visits/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RecentView> recent(@RequestParam(defaultValue = "8") int limit) {
        requireAvailable();
        return switch (readRecentVisits.execute(limit)) {
            case Success<List<VisitDocument>> success -> ResponseEntity.ok(new RecentView(
                    VisitStore.AREA, dataMode(), success.value()));
            case Failed<List<VisitDocument>> failed -> throw AppException.of(failed.failure());
        };
    }

    @Operation(summary = "The aggregate stats", operationId = "visitStats",
            description = "Totals, top topics, average duration, locale histogram — over every "
                    + "visit, or one topic when ?topicId= narrows the call.")
    @GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StatsView> stats(@RequestParam(required = false) String topicId) {
        requireAvailable();
        return switch (readVisitStats.execute(topicId)) {
            case Success<VisitStats> success -> {
                VisitStats stats = success.value();
                yield ResponseEntity.ok(new StatsView(VisitStore.AREA, dataMode(),
                        stats.totalVisits(), stats.topicVisits(), stats.filteredByTopic(),
                        stats.avgDurationMs(), stats.topTopics(), stats.localeHistogram()));
            }
            case Failed<VisitStats> failed -> throw AppException.of(failed.failure());
        };
    }

    @Operation(summary = "The aggregation pipelines, rendered as BSON", operationId = "explainPipelines",
            description = "The exact $group/$sort/$limit stage arrays the live store sends — "
                    + "what MongoDB will run, inspectable from the console. No server round "
                    + "trip: works in fake mode too.")
    @GetMapping(value = "/explain", produces = MediaType.APPLICATION_JSON_VALUE)
    ExplainView explain() {
        return new ExplainView(VisitStore.AREA, dataMode(),
                "the aggregation pipelines the live adapter builds — server-ready BSON",
                visitStore.explainPipelines());
    }

    @Operation(summary = "Flip this area fake/live", operationId = "mongoMode",
            description = "live: activates lazily (ping + indexes) and answers the live "
                    + "database name; a live request while MongoDB is unreachable leaves the "
                    + "area fake and answers an honest hint, never an error. fake: back to "
                    + "the seeded in-memory store.")
    @PostMapping(value = "/mode", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ModeView> mode(@RequestBody ModeRequest body) {
        requireAvailable();
        String requested = body != null && body.dataMode() != null ? body.dataMode() : "fake";
        if ("fake".equals(requested)) {
            visitStore.useFake();
            return ResponseEntity.ok(new ModeView(
                    VisitStore.AREA, "fake", "fake", null, null));
        }
        if (!"live".equals(requested)) {
            throw new ValidationException(
                    "dataMode must be \"fake\" or \"live\"",
                    Map.of("fields", Map.of("dataMode", "must be \"fake\" or \"live\"")));
        }
        return switch (visitStore.attemptLive()) {
            case Success<String> success -> ResponseEntity.ok(new ModeView(
                    VisitStore.AREA, "live", "live", success.value(), null));
            case Failed<String> failed -> ResponseEntity.ok(new ModeView(
                    VisitStore.AREA, "fake", "live", null,
                    "live requested but the area stayed fake: " + failed.failure().message()));
        };
    }

    // ---- edge helpers ----

    /** The area's mode as the wire spells it. */
    private String dataMode() {
        return scenarioStore.dataModeFor(VisitStore.AREA)
                == ScenarioDataMode.LIVE ? "live" : "fake";
    }

    /** The 422 mapping for semantic validation at this edge, same envelope as every failure. */
    private static ResponseEntity<ErrorResponse> unprocessable(Failure failure,
            HttpServletRequest request) {
        return ResponseEntity.status(422)
                .body(ErrorResponse.of(failure.kind().errorCode(), failure.message(),
                        requestId(request), failure.details()));
    }

    /** ScenarioStore exhibit: with injectFailure on, the demo dependency is "down" — 503. */
    private void requireAvailable() {
        if (scenarioStore.get().injectFailure()) {
            throw new UnavailableException(
                    "mongo demos are unavailable by scenario (injectFailure=true)",
                    Map.of("area", VisitStore.AREA));
        }
    }

    /** The request id the filter stamped — same fallback chain as the advice's. */
    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof String value && !value.isEmpty()) {
            return value;
        }
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
    }
}

package guide.domain.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The explorer's pure exhibit over a hand-built table: filtering, the stable
 * display sort, and every verdict the match endpoint can produce — including
 * the ranking story (exact beats variable beats catch-all) the demo teaches.
 */
class RouteTableExhibitTest {

    private static final List<RouteEntry> TABLE = List.of(
            entry("/api/content", "GET", "ContentController", "list"),
            entry("/api/content/{topicId}", "GET", "ContentController", "detail"),
            entry("/api/content/**", "GET", "ContentController", "detail"),
            entry("/api/health", "GET", "HealthController", "health"),
            entry("/api/demo/scenario", "PATCH", "ScenarioController", "patch"),
            entry("/anything/**", "GET", "AnythingController", "catchAll"));

    private static RouteEntry entry(String pattern, String method, String controller,
            String handler) {
        return new RouteEntry(pattern, method == null ? List.of() : List.of(method),
                controller, "bean", handler, List.of(), List.of());
    }

    private final RouteTableExhibit exhibit = new RouteTableExhibit();

    @Test
    void prefixFilterKeepsOnlyMatchingRowsAndSortsDeterministically() {
        RouteTableExhibit.TableView view = exhibit.view(TABLE, "/api/content");
        assertEquals("/api/content", view.prefix());
        assertEquals(3, view.total());
        assertEquals("/api/content", view.routes().getFirst().pattern());
        // display order is the pattern STRING ascending: "*" sorts before "{"
        assertEquals("/api/content/{topicId}", view.routes().getLast().pattern());

        assertEquals(6, exhibit.view(TABLE, null).total());
        assertEquals(6, exhibit.view(TABLE, "  ").total());
        assertNull(exhibit.view(TABLE, null).prefix());
    }

    @Test
    void explainRanksExactOverVariableOverCatchAll() {
        RouteTableExhibit.MatchReport report = exhibit.explain(TABLE, "GET",
                "/api/content/java-language");
        assertEquals(RouteTableExhibit.VERDICT_MATCHED, report.verdict());
        assertTrue(report.matched());
        assertEquals("/api/content/{topicId}", report.winner().pattern());
        assertEquals("java-language", report.captured().get("topicId"));
        // candidates: the variable (rank 1) before the catch-all (rank 2);
        // the two non-matching rows are counted, not listed
        assertEquals(2, report.candidates().size());
        assertEquals("/api/content/{topicId}", report.candidates().get(0).entry().pattern());
        assertEquals(1, report.candidates().get(0).score());
        assertEquals("/api/content/**", report.candidates().get(1).entry().pattern());
        assertTrue(report.candidates().get(1).catchAll());
        assertEquals(4, report.nonMatched());
        assertEquals(6, report.considered());
    }

    @Test
    void exactRouteBeatsVariableForItsOwnPath() {
        RouteTableExhibit.MatchReport report = exhibit.explain(TABLE, "GET", "/api/content");
        assertEquals("/api/content", report.winner().pattern());
        assertEquals(0, report.candidates().get(0).score());
        assertTrue(report.candidates().get(0).explanation().contains("maximally specific"));
    }

    @Test
    void pathMatchButWrongMethodIsATypedMethodNotAllowedVerdict() {
        RouteTableExhibit.MatchReport report = exhibit.explain(TABLE, "POST", "/api/health");
        assertEquals(RouteTableExhibit.VERDICT_METHOD_NOT_ALLOWED, report.verdict());
        assertNull(report.winner());
        assertEquals(1, report.candidates().size());
        assertTrue(report.candidates().get(0).pathMatched());
        assertTrue(!report.candidates().get(0).methodMatched());
        assertTrue(report.candidates().get(0).explanation().contains("not POST"));
    }

    @Test
    void nothingMatchesIsATypedNoMatchVerdict() {
        RouteTableExhibit.MatchReport report = exhibit.explain(TABLE, "GET", "/api/definitely/missing");
        assertEquals(RouteTableExhibit.VERDICT_NO_MATCH, report.verdict());
        assertTrue(!report.matched());
        assertNull(report.winner());
        assertTrue(report.candidates().isEmpty());
        assertEquals(6, report.nonMatched());
    }

    @Test
    void aMappingWithNoMethodsAnswersAnyMethod() {
        RouteEntry anyMethod = entry("/open/**", null, "OpenController", "handle");
        RouteTableExhibit.MatchReport report = exhibit.explain(List.of(anyMethod), "DELETE",
                "/open/whatever");
        assertEquals(RouteTableExhibit.VERDICT_MATCHED, report.verdict());
        assertEquals("/open/**", report.winner().pattern());
    }
}

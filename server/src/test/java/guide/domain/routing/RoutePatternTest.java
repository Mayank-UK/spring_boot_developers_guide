package guide.domain.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RoutePattern against VERIFIED Spring 7.0.9 behavior: every expectation
 * below was probed against PathPatternParser/PathPattern directly (scores,
 * catch-all flags, match results), so this suite is the contract that the
 * teaching model keeps mirroring the framework — if Spring changes, these
 * numbers say so loudly.
 */
class RoutePatternTest {

    private static RoutePattern.Match match(String spec, String path) {
        return RoutePattern.parse(spec).match(RoutePattern.segmentsOf(path));
    }

    @Test
    void exactLiteralsMatchWholeSegmentsOnly() {
        assertTrue(match("/api/content", "/api/content").matched());
        assertFalse(match("/api/content", "/api/content/jvm").matched());
        assertFalse(match("/api/content", "/api/contentX").matched());
        assertEquals(List.of("api", "content"), RoutePattern.segmentsOf("/api/content"));
    }

    @Test
    void matchingIsCaseSensitiveWithNoTrailingSlashTolerance() {
        assertFalse(match("/API/content", "/api/content").matched());
        // "/api/content/" carries an empty trailing segment: a literal cannot match it
        assertFalse(match("/api/content", "/api/content/").matched());
        // ...but a single-segment wildcard can — exactly what Spring does
        assertTrue(match("/api/content/*", "/api/content/").matched());
    }

    @Test
    void variablesCaptureExactlyOneSegment() {
        RoutePattern.Match hit = match("/api/content/{topicId}", "/api/content/jvm");
        assertTrue(hit.matched());
        assertEquals(java.util.Map.of("topicId", "jvm"), hit.variables());
        assertFalse(match("/api/content/{topicId}", "/api/content/jvm/extra").matched());
    }

    @Test
    void regexConstrainedVariablesRejectNonMatchingSegments() {
        assertTrue(match("/specimens/{id:\\d+}", "/specimens/42").matched());
        assertFalse(match("/specimens/{id:\\d+}", "/specimens/nope").matched());
        // brace-aware regex split: \d{2} keeps its inner brace
        assertTrue(match("/specimens/{id:\\d{2}}", "/specimens/77").matched());
        assertFalse(match("/specimens/{id:\\d{2}}", "/specimens/777").matched());
    }

    @Test
    void trailingCatchAllsSwallowZeroOrMoreSegments() {
        assertTrue(match("/api/content/**", "/api/content").matched());
        assertTrue(match("/api/content/**", "/api/content/a/b/c").matched());
        assertFalse(match("/api/content/**", "/api/contentX").matched());
    }

    @Test
    void namedRestCaptureKeepsItsLeadingSlash() {
        RoutePattern.Match hit = match("/api/{*rest}", "/api/a/b/c");
        assertTrue(hit.matched());
        assertEquals(java.util.Map.of("rest", "/a/b/c"), hit.variables());
        // Spring-verified: the captured value includes the leading slash
        assertTrue(match("/api/demo/routing/specimens/{*rest}", "/api/demo/routing/specimens/42")
                .matched());
    }

    @Test
    void leadingCatchAllMatchesAtAnyDepth() {
        assertTrue(match("**/health", "/health").matched());
        assertTrue(match("**/health", "/x/y/health").matched());
        assertFalse(match("**/health", "/health/x").matched());
    }

    @Test
    void inLiteralWildcardsMatchWithinOneSegment() {
        assertTrue(match("/card/any-*", "/card/any-thing").matched());
        assertFalse(match("/card/any-*", "/card/other-thing").matched());
        assertFalse(match("/card/any-*", "/card/any-/deep").matched());
        assertTrue(match("/c?rd/green", "/cArd/green").matched());
    }

    @Test
    void middleCatchAllsAreRejectedLikeSpringRejectsThem() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> RoutePattern.parse("/a/**/b"));
        assertTrue(rejected.getMessage().contains("start or end"));
        assertThrows(IllegalArgumentException.class, () -> RoutePattern.parse("api/content"));
        assertThrows(IllegalArgumentException.class, () -> RoutePattern.parse("/api//content"));
    }

    @Test
    void specificityNumbersMirrorTheFramework() {
        assertEquals(0, RoutePattern.parse("/api/content").score());
        assertEquals(1, RoutePattern.parse("/api/content/{topicId}").score());
        assertEquals(100, RoutePattern.parse("/api/content/*").score());
        assertEquals(0, RoutePattern.parse("/a?c").score());
        assertEquals(100, RoutePattern.parse("/a*c").score());
        assertTrue(RoutePattern.parse("/api/content/**").isCatchAll());
        assertTrue(RoutePattern.parse("/api/{*rest}").isCatchAll());
        assertFalse(RoutePattern.parse("/api/content/{topicId}").isCatchAll());
        // verified normalized lengths (literals count chars, ?/* /vars count 1, catch-alls 0)
        assertEquals(4, RoutePattern.parse("/abc").normalizedLength());
        assertEquals(14, RoutePattern.parse("/api/content/{topicId}").normalizedLength());
        assertEquals(14, RoutePattern.parse("/api/content/*").normalizedLength());
        assertEquals(28, RoutePattern.parse("/api/demo/routing/specimens/**").normalizedLength());
    }

    @Test
    void specificityOrderingIsExactThenVariableThenStarThenCatchAll() {
        RoutePattern exact = RoutePattern.parse("/api/demo/routing/table");
        RoutePattern variable = RoutePattern.parse("/api/demo/routing/{section}");
        RoutePattern star = RoutePattern.parse("/api/demo/routing/*");
        RoutePattern catchAll = RoutePattern.parse("/api/demo/routing/{*rest}");
        assertTrue(RoutePattern.SPECIFICITY.compare(exact, variable) < 0);
        assertTrue(RoutePattern.SPECIFICITY.compare(variable, star) < 0);
        assertTrue(RoutePattern.SPECIFICITY.compare(star, catchAll) < 0);
        // same score, longer literal wins: verified comparator tie-break
        RoutePattern shortVar = RoutePattern.parse("/api/{area}");
        assertTrue(RoutePattern.SPECIFICITY.compare(variable, shortVar) < 0);
    }
}

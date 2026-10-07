package guide.domain.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.http.HeadersExhibit.HeaderView;
import guide.domain.http.HeadersExhibit.InspectReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HeadersExhibit unit tests — the pure classification table behind the
 * http-web inspector demo: categories, case-insensitivity, sensitivity rules
 * (including the defensive fragment match for non-standard secret names),
 * sorting, and Content-Type parsing.
 */
class HeadersExhibitTest {

    private final HeadersExhibit exhibit = new HeadersExhibit();

    @Test
    void classifiesKnownHeadersAcrossCategoriesCaseInsensitively() {
        InspectReport report = exhibit.inspect("GET", "/api/demo/http/inspect", "a=1",
                List.of(new HeadersExhibit.IncomingHeader("Accept", "application/json"),
                        new HeadersExhibit.IncomingHeader("if-none-match", "\"v1\""),
                        new HeadersExhibit.IncomingHeader("Content-Type", "application/json"),
                        new HeadersExhibit.IncomingHeader("COOKIE", "sid=1"),
                        new HeadersExhibit.IncomingHeader("Origin", "http://localhost:5176")),
                HeadersExhibit.IncomingBody.absent());

        // Names echo in the casing received; SORTING is case-insensitive
        // ("content-type" sorts before "cookie": 'n' < 'o').
        assertEquals(List.of("accept", "content-type", "cookie", "if-none-match", "origin"),
                report.headers().stream()
                        .map(header -> header.name().toLowerCase(java.util.Locale.ROOT)).toList());
        assertEquals("negotiation", find(report, "accept").category());
        assertEquals("caching", find(report, "if-none-match").category());
        assertEquals("content", find(report, "content-type").category());
        assertEquals("session", find(report, "cookie").category());
        assertEquals("cors", find(report, "origin").category());
        assertTrue(report.headers().stream().allMatch(HeaderView::known));
    }

    @Test
    void unknownHeadersClassifyAsOtherButStillEcho() {
        InspectReport report = exhibit.inspect("GET", "/x", null,
                List.of(new HeadersExhibit.IncomingHeader("x-teaching-flag", "demo-123")),
                HeadersExhibit.IncomingBody.absent());

        HeaderView header = find(report, "x-teaching-flag");
        assertEquals("demo-123", header.value());
        assertEquals("other", header.category());
        assertFalse(header.known());
        assertFalse(header.sensitive());
    }

    @Test
    void secretBearingHeadersAreFlaggedSensitiveEvenWhenUnknown() {
        InspectReport report = exhibit.inspect("GET", "/x", null,
                List.of(new HeadersExhibit.IncomingHeader("Authorization", "Bearer abc"),
                        new HeadersExhibit.IncomingHeader("Cookie", "sid=1"),
                        new HeadersExhibit.IncomingHeader("X-API-Key", "k-42"),
                        new HeadersExhibit.IncomingHeader("X-Auth-Token", "t")),
                HeadersExhibit.IncomingBody.absent());

        assertTrue(find(report, "Authorization").sensitive());
        assertEquals("auth", find(report, "Authorization").category());
        assertTrue(find(report, "Cookie").sensitive());
        assertTrue(find(report, "X-API-Key").sensitive(), "fragment match: 'key' in the name");
        assertFalse(find(report, "X-API-Key").known());
        assertTrue(find(report, "X-Auth-Token").sensitive());
    }

    @Test
    void duplicateHeaderRowsAreKeptAndSortedDeterministically() {
        InspectReport report = exhibit.inspect("GET", "/x", "z=1&z=2",
                List.of(new HeadersExhibit.IncomingHeader("z-header", "second"),
                        new HeadersExhibit.IncomingHeader("z-header", "first")),
                HeadersExhibit.IncomingBody.absent());

        assertEquals(2, report.headers().size());
        assertEquals("first", report.headers().get(0).value(), "same name sorted by value");
        assertEquals("second", report.headers().get(1).value());
    }

    @Test
    void bodyViewParsesMediaTypeParametersAndJsonShape() {
        HeadersExhibit.IncomingBody body = new HeadersExhibit.IncomingBody(true,
                "application/json;charset=UTF-8", "object", 3);
        InspectReport report = exhibit.inspect("POST", "/x", null, List.of(), body);

        assertTrue(report.body().present());
        assertEquals("application/json;charset=UTF-8", report.body().contentType());
        assertEquals("application/json", report.body().mediaType());
        assertEquals("json object", report.body().parsedAs());
        assertEquals(3, report.body().topLevelEntries());
    }

    @Test
    void absentBodyAndBlankInputsNormalizeCleanly() {
        InspectReport report = exhibit.inspect("GET", "/x", "  ",
                List.of(), null);

        assertEquals("/x", report.path(), "path echoes as received (the use case trims)");
        assertNull(report.query(), "a blank query string reports as null, not whitespace");
        assertFalse(report.body().present());
        assertEquals("absent", report.body().parsedAs());
        assertNull(report.body().contentType());
        assertTrue(report.headers().isEmpty());
    }

    private static HeaderView find(InspectReport report, String name) {
        return report.headers().stream()
                .filter(header -> header.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("header not echoed: " + name));
    }
}

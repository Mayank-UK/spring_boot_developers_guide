package guide.domain.reqres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.reqres.RequestSurfaceExhibit.AttributeView;
import guide.domain.reqres.RequestSurfaceExhibit.BoundBody;
import guide.domain.reqres.RequestSurfaceExhibit.Incoming;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingAttribute;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingCookie;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingHeader;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingParam;
import guide.domain.reqres.RequestSurfaceExhibit.RawSurface;
import guide.domain.reqres.RequestSurfaceExhibit.Report;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RequestSurfaceExhibit unit tests: the classification stamps every bound
 * value with its sugar, the requestId attribute gets the topic-08 note,
 * raw reads count only fields that carried a value, and null inputs are
 * tolerated (an inspector never polices the wire — it reports it).
 */
class RequestSurfaceExhibitTest {

    private final RequestSurfaceExhibit exhibit = new RequestSurfaceExhibit();

    @Test
    void boundValuesAreStampedWithTheirSugar() {
        Report report = exhibit.inspect(new Incoming("get",
                List.of(new IncomingParam("channel", "demo")),
                List.of(new IncomingHeader("Accept", "application/json")),
                List.of(new IncomingCookie("reqres-visit", "42")),
                BoundBody.absent(),
                new RawSurface("/api/demo/reqres/inspect", "channel=demo", null, null, List.of()),
                List.of()));

        assertEquals("GET", report.method(), "method normalizes to uppercase");
        assertEquals("channel", report.bound().params().getFirst().name());
        assertEquals("@RequestParam", report.bound().params().getFirst().via());
        assertEquals("@RequestHeader", report.bound().headers().getFirst().via());
        assertEquals("@CookieValue", report.bound().cookies().getFirst().via());
        assertEquals(3, report.summary().boundValues());
        assertEquals(2, report.summary().rawReads(), "only requestURI and queryString carried values");
    }

    @Test
    void requestIdAttributeCarriesTheMiddlewareNote() {
        Report report = exhibit.inspect(new Incoming("POST",
                List.of(), List.of(), List.of(),
                new BoundBody(true, "object", 2),
                new RawSurface("/x", null, null, "application/json", List.of("en")),
                List.of(new IncomingAttribute("requestId", "req-7"),
                        new IncomingAttribute("custom", "value"))));

        AttributeView requestId = report.attributes().stream()
                .filter(attribute -> attribute.name().equals("requestId")).findFirst().orElseThrow();
        assertTrue(requestId.note().contains("request-id filter"));
        assertEquals("in-process cargo — set server-side, never traveled the wire",
                report.attributes().stream()
                        .filter(attribute -> attribute.name().equals("custom")).findFirst().orElseThrow().note());
        assertEquals(1, report.summary().boundValues(), "params+headers+cookies empty; only the body counts as bound");
        assertTrue(report.summary().notes().stream()
                .anyMatch(note -> note.contains("middleware attribute (requestId)")));
    }

    @Test
    void nullInputsAreToleratedEverywhere() {
        Report report = exhibit.inspect(new Incoming("GET",
                null, null, null, null,
                new RawSurface(null, null, null, null, null),
                null));

        assertTrue(report.bound().params().isEmpty());
        assertTrue(report.bound().headers().isEmpty());
        assertTrue(report.bound().cookies().isEmpty());
        assertTrue(report.bound().body().present() == false
                && report.bound().body().parsedAs().equals("absent"));
        assertTrue(report.attributes().isEmpty());
        assertEquals(0, report.summary().rawReads());
        assertTrue(report.raw().locales().isEmpty(), "null locale list normalizes to empty");
    }

    @Test
    void blankMethodNormalizesToUnknownSentinel() {
        assertEquals("UNKNOWN", new Incoming("  ", null, null, null, null,
                new RawSurface("/x", null, null, null, null), null).method());
        assertEquals("UNKNOWN", new Incoming(null, null, null, null, null,
                new RawSurface("/x", null, null, null, null), null).method());
    }
}

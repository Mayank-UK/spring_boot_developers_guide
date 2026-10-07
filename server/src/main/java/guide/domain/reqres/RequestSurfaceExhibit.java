package guide.domain.reqres;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure exhibit behind the reqres inspector demo (topic 09): given the values
 * MVC BOUND on one side and the RAW servlet surface on the other, annotate
 * every bound value with the annotation sugar that carried it, carry notes
 * for the raw side, and summarize the split. No Spring type crosses this
 * boundary — the controller turns the request into plain data first (the
 * dependency rule), and this table does the teaching.
 *
 * The classification is the point: every bound value maps to exactly one
 * annotation (@RequestParam, @RequestHeader, @CookieValue, @RequestBody),
 * while every raw field had to be read by hand from the request object —
 * two surfaces over one wire message.
 */
public final class RequestSurfaceExhibit {

    /** One value the framework bound for the handler — plus the sugar that carried it. */
    public record BoundValue(String name, String value, String via) {
    }

    /** What @RequestBody bound, when a body was present (normalized to absent, never null). */
    public record BoundBody(boolean present, String parsedAs, int topLevelEntries) {

        public static BoundBody absent() {
            return new BoundBody(false, "absent", 0);
        }
    }

    /** The raw servlet surface — values the handler reads by hand, no sugar. */
    public record RawSurface(String requestURI, String queryString, String authType,
            String contentType, List<String> locales) {

        public RawSurface {
            locales = locales == null ? List.of() : List.copyOf(locales);
        }
    }

    /** One request attribute: in-process cargo that never traveled the wire. */
    public record AttributeView(String name, String value, String note) {
    }

    /** The bound half of the report, grouped by the sugar that carried it. */
    public record BoundSide(List<BoundValue> params, List<BoundValue> headers,
            List<BoundValue> cookies, BoundBody body) {
    }

    /** Counters + honest notes about what each side actually did. */
    public record Summary(int boundValues, int rawReads, int attributes, List<String> notes) {
    }

    /** The inspector's answer: bound vs raw vs attributes, plus the summary. */
    public record Report(String method, BoundSide bound, RawSurface raw,
            List<AttributeView> attributes, Summary summary) {
    }

    /** Every plain input carries a name and a value — that is all classification needs. */
    public interface Named {
        String name();

        String value();
    }

    /** Plain inputs (controller-side extraction, before any classification). */
    public record Incoming(String method, List<IncomingParam> params, List<IncomingHeader> headers,
            List<IncomingCookie> cookies, BoundBody body, RawSurface raw,
            List<IncomingAttribute> attributes) {

        public Incoming {
            method = method == null || method.isBlank() ? "UNKNOWN" : method.trim().toUpperCase(Locale.ROOT);
            body = body == null ? BoundBody.absent() : body;
            params = sorted(params);
            headers = sorted(headers);
            cookies = sorted(cookies);
            attributes = sorted(attributes);
        }
    }

    public record IncomingParam(String name, String value) implements Named {
    }

    public record IncomingHeader(String name, String value) implements Named {
    }

    public record IncomingCookie(String name, String value) implements Named {
    }

    public record IncomingAttribute(String name, String value) implements Named {
    }

    /** Attribute the request-id filter stamps before any controller runs (topic 08). */
    static final String REQUEST_ID_ATTRIBUTE = "requestId";

    /** Classifies + summarizes one incoming request: sugars on one side, raw reads on the other. */
    public Report inspect(Incoming incoming) {
        List<BoundValue> params = via(incoming.params(), "@RequestParam");
        List<BoundValue> headers = via(incoming.headers(), "@RequestHeader");
        List<BoundValue> cookies = via(incoming.cookies(), "@CookieValue");
        List<AttributeView> attributes = incoming.attributes().stream()
                .map(attribute -> new AttributeView(attribute.name(), attribute.value(),
                        noteFor(attribute.name())))
                .toList();

        int boundValues = params.size() + headers.size() + cookies.size()
                + (incoming.body().present() ? 1 : 0);
        int rawReads = rawReadCount(incoming.raw());
        return new Report(incoming.method(),
                new BoundSide(params, headers, cookies, incoming.body()),
                incoming.raw(), attributes,
                new Summary(boundValues, rawReads, attributes.size(),
                        notes(incoming, attributes)));
    }

    /** The classify half: stamp every plain value with the annotation that bound it. */
    private static List<BoundValue> via(List<? extends Named> values, String sugar) {
        return values.stream()
                .map(value -> new BoundValue(value.name(), value.value(), sugar))
                .toList();
    }

    /** Attributes middleware stamped get the cross-topic note; everything else is honest cargo. */
    private static String noteFor(String name) {
        if (name.equals(REQUEST_ID_ATTRIBUTE)) {
            return "stamped by topic 08's request-id filter — middleware ran before any controller";
        }
        return "in-process cargo — set server-side, never traveled the wire";
    }

    /** The summarize half: which raw fields actually carried a value this time. */
    private static int rawReadCount(RawSurface raw) {
        int reads = 0;
        reads += present(raw.requestURI()) ? 1 : 0;
        reads += present(raw.queryString()) ? 1 : 0;
        reads += present(raw.authType()) ? 1 : 0;
        reads += present(raw.contentType()) ? 1 : 0;
        reads += raw.locales().size();
        return reads;
    }

    private static List<String> notes(Incoming incoming, List<AttributeView> attributes) {
        List<String> notes = new ArrayList<>();
        if (present(incoming.raw().queryString())) {
            notes.add("the query string was never parsed by hand — @RequestParam had already bound it");
        }
        if (incoming.body().present()) {
            notes.add("@RequestBody bound the whole body after HttpMessageConverter negotiation");
        }
        if (attributes.stream().anyMatch(attribute -> attribute.name().equals(REQUEST_ID_ATTRIBUTE))) {
            notes.add("a middleware attribute (requestId) rode along — the raw surface's exclusive cargo");
        }
        notes.add("MVC bound " + (incoming.params().size() + incoming.headers().size()
                + incoming.cookies().size()) + " value(s) via annotations; the raw object still owned "
                + rawReadCount(incoming.raw()) + " field read(s) the sugars do not cover");
        return List.copyOf(notes);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static <T extends Named> List<T> sorted(List<T> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .sorted(Comparator.comparing(Named::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}

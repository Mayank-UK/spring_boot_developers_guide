package guide.domain.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The live exhibit behind topic 03's header inspector: pure classification of
 * HTTP header names into the categories you actually reason about when you
 * read a request (auth, content, caching, session, negotiation, CORS), plus
 * the Content-Type parsing the POST variant reports. No Spring, no I/O, no
 * logging — the controller hands over plain strings and this table annotates
 * them. That purity is the teaching point: the wire's metadata becomes plain
 * data before any business logic sees it.
 *
 * Sensitivity is metadata, not suppression: an Authorization or Cookie header
 * the caller deliberately sent to THIS inspector is echoed back to that same
 * caller, but flagged {@code sensitive} so every consumer (client UI, tests,
 * future logs) knows to treat the value as a secret. Nothing in this exhibit
 * ever writes a value anywhere but the returned report.
 */
public final class HeadersExhibit {

    /** Header-name categories, lowercase on the wire (client renders them as badges). */
    public enum Category {
        AUTH("auth"), CONTENT("content"), CACHING("caching"), SESSION("session"),
        NEGOTIATION("negotiation"), CORS("cors"), OTHER("other");

        private final String wireName;

        Category(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** Header names classified by category, matched case-insensitively. */
    private static final Map<String, Category> CATEGORIES = Map.ofEntries(
            Map.entry("authorization", Category.AUTH),
            Map.entry("proxy-authorization", Category.AUTH),
            Map.entry("www-authenticate", Category.AUTH),
            Map.entry("content-type", Category.CONTENT),
            Map.entry("content-length", Category.CONTENT),
            Map.entry("content-encoding", Category.CONTENT),
            Map.entry("content-language", Category.CONTENT),
            Map.entry("content-disposition", Category.CONTENT),
            Map.entry("content-range", Category.CONTENT),
            Map.entry("if-modified-since", Category.CACHING),
            Map.entry("if-none-match", Category.CACHING),
            Map.entry("if-match", Category.CACHING),
            Map.entry("if-unmodified-since", Category.CACHING),
            Map.entry("cache-control", Category.CACHING),
            Map.entry("etag", Category.CACHING),
            Map.entry("expires", Category.CACHING),
            Map.entry("pragma", Category.CACHING),
            Map.entry("cookie", Category.SESSION),
            Map.entry("set-cookie", Category.SESSION),
            Map.entry("accept", Category.NEGOTIATION),
            Map.entry("accept-encoding", Category.NEGOTIATION),
            Map.entry("accept-language", Category.NEGOTIATION),
            Map.entry("accept-charset", Category.NEGOTIATION),
            Map.entry("vary", Category.NEGOTIATION),
            Map.entry("origin", Category.CORS),
            Map.entry("access-control-request-method", Category.CORS),
            Map.entry("access-control-request-headers", Category.CORS),
            Map.entry("access-control-allow-origin", Category.CORS),
            Map.entry("access-control-allow-methods", Category.CORS),
            Map.entry("access-control-allow-headers", Category.CORS),
            Map.entry("access-control-max-age", Category.CORS));

    /** Known secret-bearing header names — echoed, but always flagged. */
    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie");

    /**
     * Substrings that make an UNKNOWN header sensitive anyway: x-api-key,
     * x-auth-token, x-secret-thing all carry credentials even though no
     * standard names them. Input is hostile; secrets do not get a pass for
     * being non-standard.
     */
    private static final List<String> SENSITIVE_FRAGMENTS = List.of("key", "token", "secret", "password");

    /** One request header as received, before classification. */
    public record IncomingHeader(String name, String value) {

        public IncomingHeader {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    /** The body half of the request: what arrived, and what the edge parsed it into. */
    public record IncomingBody(boolean present, String contentType, String jsonKind, int topLevelEntries) {

        /** The GET-variant body: nothing arrived, so there is nothing to report. */
        public static IncomingBody absent() {
            return new IncomingBody(false, null, "absent", 0);
        }
    }

    /** One echoed header: the received name/value plus its classification. */
    public record HeaderView(String name, String value, String category, boolean known, boolean sensitive) {
    }

    /** What the server made of the request body, if any. */
    public record BodyView(boolean present, String contentType, String mediaType,
            String parsedAs, int topLevelEntries) {
    }

    /** The inspector's report: the request, annotated. */
    public record InspectReport(String method, String path, String query,
            List<HeaderView> headers, BodyView body) {

        public InspectReport {
            headers = List.copyOf(headers);
        }
    }

    /** Builds the report: headers classified and sorted by name (case-insensitive). */
    public InspectReport inspect(String method, String path, String rawQuery,
            List<IncomingHeader> headers, IncomingBody body) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");

        List<IncomingHeader> incoming = headers == null ? List.of() : List.copyOf(headers);
        List<HeaderView> views = new ArrayList<>(incoming.stream()
                .map(header -> {
                    Classification classification = classify(header.name());
                    return new HeaderView(header.name(), header.value(),
                            classification.category().wireName(),
                            classification.known(),
                            classification.sensitive());
                })
                .toList());
        views.sort((left, right) -> {
            int byName = left.name().toLowerCase(Locale.ROOT)
                    .compareTo(right.name().toLowerCase(Locale.ROOT));
            return byName != 0 ? byName : left.value().compareTo(right.value());
        });

        IncomingBody incomingBody = body == null ? IncomingBody.absent() : body;
        return new InspectReport(method, path, emptyToNull(rawQuery), views,
                bodyView(incomingBody));
    }

    /** One header's classification: category, whether the name is standard-known, sensitivity. */
    private record Classification(Category category, boolean known, boolean sensitive) {
    }

    private static Classification classify(String name) {
        String lowered = name.trim().toLowerCase(Locale.ROOT);
        Category category = CATEGORIES.get(lowered);
        boolean known = category != null;
        boolean sensitive = SENSITIVE.contains(lowered)
                || SENSITIVE_FRAGMENTS.stream().anyMatch(lowered::contains);
        return new Classification(known ? category : Category.OTHER, known, sensitive);
    }

    private static BodyView bodyView(IncomingBody body) {
        String contentType = body.contentType();
        String mediaType = null;
        if (contentType != null && !contentType.isBlank()) {
            mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        }
        String parsedAs = switch (body.jsonKind() == null ? "absent" : body.jsonKind()) {
            case "object" -> "json object";
            case "array" -> "json array";
            case "scalar" -> "json scalar";
            default -> "absent";
        };
        return new BodyView(body.present(), emptyToNull(contentType), mediaType,
                parsedAs, Math.max(0, body.topLevelEntries()));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

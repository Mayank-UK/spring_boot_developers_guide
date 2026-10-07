package guide.domain.reqres;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure exhibit behind the reqres methods demo (topic 09): the canonical
 * response decision for each HTTP verb against ONE resource path. The
 * teaching point is WHERE the decision lives — this table, in the domain.
 * The controller asks "what does this verb answer?" and maps the decision
 * onto ResponseEntity without a switch of its own: status, headers, and
 * body arrive as values, chosen per verb's contract.
 */
public final class MethodsExhibit {

    /** Status + headers + optional body + the reason, decided per verb. */
    public record Decision(int status, Map<String, String> headers, OutcomeBody body) {

        public Decision {
            headers = Map.copyOf(headers);
        }
    }

    /** The representation sent for GET/POST/PUT/DELETE; PATCH's 204 carries none (null). */
    public record OutcomeBody(String method, String semantics, Map<String, String> facts) {

        public OutcomeBody {
            facts = Map.copyOf(facts);
        }
    }

    /** Custom header every decision carries: the one-line WHY of its status. */
    public static final String TEACHING_NOTE = "x-teaching-note";

    /** The single path every verb in the demo answers. */
    public static final String RESOURCE_PATH = "/api/demo/reqres/methods";

    private static final Set<String> SUPPORTED = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    public boolean supports(String method) {
        return method != null && SUPPORTED.contains(normalize(method));
    }

    /**
     * Decides the response for one verb. {@code creation} and {@code revision}
     * are the live counters the caller maintains (POST assigns a new resource
     * number, PUT versions the representation); verbs that need neither pass 0.
     *
     * @throws IllegalArgumentException for a verb outside SUPPORTED (the use
     *     case checks {@link #supports} first and answers a typed failure)
     */
    public Decision decide(String method, long creation, long revision) {
        String verb = normalize(method);
        if (!SUPPORTED.contains(verb)) {
            throw new IllegalArgumentException("unsupported method: " + method);
        }
        return switch (verb) {
            case "GET" -> new Decision(200,
                    note("200 OK — success with a representation"),
                    new OutcomeBody("GET", "safe · idempotent", Map.of(
                            "resource", "methods-demo",
                            "state", "unchanged — a read, answer with the representation")));
            case "POST" -> created(creation);
            case "PUT" -> replaced(revision);
            case "PATCH" -> new Decision(204,
                    note("204 No Content — applied; deliberately nothing to say"),
                    null);
            case "DELETE" -> new Decision(200,
                    note("200 OK — deleted, with a confirmation body; 204 is equally canonical"),
                    new OutcomeBody("DELETE", "not safe · idempotent", Map.of(
                            "deleted", "true",
                            "secondCall", "finds nothing to do — same state, idempotent")));
            default -> throw new IllegalStateException("unreachable: " + verb);
        };
    }

    private Decision created(long creation) {
        String resource = "resource-" + creation;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Location", RESOURCE_PATH + "/" + resource);
        headers.put(TEACHING_NOTE, "201 Created — Location names the new resource");
        return new Decision(201, headers,
                new OutcomeBody("POST", "not safe · not idempotent", Map.of(
                        "created", resource,
                        "location", RESOURCE_PATH + "/" + resource,
                        "replayed", "a second POST would create resource-" + (creation + 1))));
    }

    private Decision replaced(long revision) {
        String etag = "\"v" + revision + "\"";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ETag", etag);
        headers.put(TEACHING_NOTE, "200 OK — replaced; ETag versions the new representation");
        return new Decision(200, headers,
                new OutcomeBody("PUT", "not safe · idempotent", Map.of(
                        "resource", "methods-demo",
                        "etag", etag,
                        "whyIdempotent", "the same body replaces the same state — replays collapse")));
    }

    private static Map<String, String> note(String text) {
        return Map.of(TEACHING_NOTE, text);
    }

    private static String normalize(String method) {
        return method.trim().toUpperCase(Locale.ROOT);
    }
}

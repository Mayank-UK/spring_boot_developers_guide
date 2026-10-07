package guide.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The live exhibit behind topic 07's routing explorer: pure table operations
 * over the app's real route inventory — filtering (the prefix box), a stable
 * display sort, and the match explainer (given a method + path, which
 * registered pattern would Spring choose, which others also matched, and WHY
 * the winner won). No Spring, no I/O: the data adapter hands over plain
 * {@link RouteEntry} rows and this table annotates them.
 *
 * Honesty note that travels with this exhibit: the table itself is Spring's
 * real in-memory registry, read live per request; the matcher and ranking are
 * a teaching model that mirrors PathPattern's verified semantics
 * (see {@link RoutePattern}). When the two ever disagree, the wire wins and
 * the model needs fixing — the MockMvc tests pin the cases this app serves.
 */
public final class RouteTableExhibit {

    /** Verdicts the match endpoint can return, typed for the client. */
    public static final String VERDICT_MATCHED = "matched";
    public static final String VERDICT_METHOD_NOT_ALLOWED = "method_not_allowed";
    public static final String VERDICT_NO_MATCH = "no_match";

    /** The filtered table: what the prefix filter kept, in display order. */
    public record TableView(String prefix, int total, List<RouteEntry> routes) {

        public TableView {
            routes = List.copyOf(routes);
        }
    }

    /**
     * One candidate the explainer considered: the entry, whether its pattern
     * and method matched the probe, what a match captured, its specificity
     * numbers, and the one-line WHY.
     */
    public record Candidate(RouteEntry entry, boolean pathMatched, boolean methodMatched,
            Map<String, String> captured, int score, boolean catchAll,
            String explanation) {

        public Candidate {
            captured = Map.copyOf(captured);
        }
    }

    /** The explainer's full answer: verdict, winner, ranked candidates, counts. */
    public record MatchReport(String method, String path, String verdict, boolean matched,
            RouteEntry winner, Map<String, String> captured, List<Candidate> candidates,
            int considered, int nonMatched) {

        public MatchReport {
            captured = Map.copyOf(captured);
            candidates = List.copyOf(candidates);
        }
    }

    /** What a specimen route answers: which pattern caught the request and what it bound. */
    public record SpecimenReport(String pattern, Map<String, String> captured, String note) {

        public SpecimenReport {
            captured = Map.copyOf(captured);
        }

        /** Convenience for the demo controller's three live specimens. */
        public static SpecimenReport of(String pattern, Map<String, String> captured, String note) {
            return new SpecimenReport(pattern, captured, note);
        }
    }

    /** Applies the optional prefix filter and the stable display sort. */
    public TableView view(List<RouteEntry> routes, String prefix) {
        Objects.requireNonNull(routes, "routes");
        String normalized = prefix == null ? "" : prefix.trim();
        List<RouteEntry> kept = new ArrayList<>();
        for (RouteEntry route : routes) {
            if (normalized.isEmpty() || route.pattern().startsWith(normalized)) {
                kept.add(route);
            }
        }
        kept.sort(DISPLAY_ORDER);
        return new TableView(normalized.isEmpty() ? null : normalized, kept.size(), kept);
    }

    /** Deterministic display order: pattern, then controller, then handler. */
    static final Comparator<RouteEntry> DISPLAY_ORDER =
            Comparator.comparing(RouteEntry::pattern)
                    .thenComparing(RouteEntry::controller)
                    .thenComparing(RouteEntry::handler);

    /**
     * The match explainer. Every registered pattern is parsed and tried; the
     * answer ranks the path-matching patterns by specificity (winner first —
     * the same order Spring's comparator would choose), lists path-but-not-
     * method matches after them (the 405 story), and reports everything else
     * as a count. Verdicts: matched · method_not_allowed · no_match.
     */
    public MatchReport explain(List<RouteEntry> routes, String method, String path) {
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");

        List<PatternMatch> pathMatches = new ArrayList<>();
        List<Candidate> methodMismatches = new ArrayList<>();
        int nonMatched = 0;
        for (RouteEntry route : routes) {
            RoutePattern pattern;
            try {
                pattern = RoutePattern.parse(route.pattern());
            } catch (IllegalArgumentException unsupported) {
                // A registered shape this teaching model cannot parse (none
                // exist today) — counted as a non-match, never a 500.
                nonMatched++;
                continue;
            }
            RoutePattern.Match match = pattern.match(RoutePattern.segmentsOf(path));
            boolean methodMatched = route.allowsMethod(method);
            if (!match.matched()) {
                nonMatched++;
                continue;
            }
            if (methodMatched) {
                pathMatches.add(new PatternMatch(pattern, route, match.variables()));
            } else {
                methodMismatches.add(new Candidate(route, true, false, Map.of(),
                        pattern.score(), pattern.isCatchAll(),
                        "path matches, but the mapping allows "
                                + (route.methods().isEmpty() ? "any method" : route.methods())
                                + " — not " + method));
            }
        }
        pathMatches.sort(Comparator.comparing(PatternMatch::pattern, RoutePattern.SPECIFICITY));

        List<Candidate> candidates = new ArrayList<>();
        int rank = 1;
        for (PatternMatch match : pathMatches) {
            candidates.add(new Candidate(match.entry(), true, true, match.variables(),
                    match.pattern().score(), match.pattern().isCatchAll(),
                    explanationFor(match.pattern(), rank, pathMatches.size(), match.variables())));
            rank++;
        }
        candidates.addAll(methodMismatches);

        if (candidates.isEmpty()) {
            return new MatchReport(method, path, VERDICT_NO_MATCH, false,
                    null, Map.of(), List.of(), routes.size(), nonMatched);
        }
        if (pathMatches.isEmpty()) {
            return new MatchReport(method, path, VERDICT_METHOD_NOT_ALLOWED, false,
                    null, Map.of(), candidates, routes.size(), nonMatched);
        }
        PatternMatch winner = pathMatches.getFirst();
        return new MatchReport(method, path, VERDICT_MATCHED, true,
                winner.entry(), winner.variables(), candidates, routes.size(), nonMatched);
    }

    private static String explanationFor(RoutePattern pattern, int rank, int total,
            Map<String, String> variables) {
        String why = pattern.isCatchAll()
                ? "catch-all — matched, but every non-catch-all pattern outranks it"
                : "specificity score " + pattern.score()
                        + (variables.isEmpty() ? ", no captures — maximally specific"
                                : " (" + variables.size() + " captured variable"
                                        + (variables.size() == 1 ? "" : "s") + ")");
        return "rank " + rank + " of " + total + " matching — " + why;
    }

    /** Internal pairing of a parsed pattern with the route it came from. */
    private record PatternMatch(RoutePattern pattern, RouteEntry entry,
            Map<String, String> variables) {
    }

    /** Ranks any two registered patterns against each other (for tests and the table view). */
    static int compareSpecificity(String left, String right) {
        return RoutePattern.SPECIFICITY.compare(
                RoutePattern.parse(left), RoutePattern.parse(right));
    }
}

package guide.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A teaching re-implementation of Spring's {@code PathPattern} — the pattern
 * dialect every @GetMapping path speaks on Boot 4 (parsed once by
 * PathPatternParser, matched per request). Pure domain: no Spring imports,
 * no I/O. The semantics, scores, and ranking below were verified against
 * Spring 7.0.9 (the version this app runs) and mirror it deliberately:
 *
 * - segments match whole-for-whole; matching is case-sensitive, and there is
 *   NO trailing-slash tolerance ("/api/content/" is a different path);
 * - {var} captures exactly one segment; {var:regex} only if the segment
 *   matches the whole regex; a bare * is a one-segment wildcard (it even
 *   matches the empty trailing segment of "/api/content/");
 * - {*var} (or a trailing **) consumes zero or more segments to the end and
 *   is a CATCH-ALL; ** may also lead a pattern (a leading double-star
 *   matches at any depth). A ** in the middle is invalid — Spring rejects
 *   it at parse time and so does this model;
 * - inside a literal segment ? matches one character and * matches any run;
 * - specificity: a catch-all always loses to a non-catch-all; then the lower
 *   score wins (a captured variable costs 1, an unbounded wildcard 100); then
 *   the longer normalized length wins (literals count their characters, ? /*
 *   /variables count 1, catch-alls count 0). Exact literals are maximally
 *   specific because they cost nothing.
 */
public final class RoutePattern {

    /**
     * Orders patterns most-specific first, mirroring Spring's
     * PathPattern.SPECIFICITY_COMPARATOR: catch-all flag, then score, then
     * normalized length (longer = more specific).
     */
    public static final Comparator<RoutePattern> SPECIFICITY =
            Comparator.comparingInt((RoutePattern p) -> p.catchAll ? 1 : 0)
                    .thenComparingInt(p -> p.score)
                    .thenComparing(p -> -p.normalizedLength);

    /** One matched pattern: did it match, and what did it capture. */
    public record Match(boolean matched, Map<String, String> variables) {

        static final Match NONE = new Match(false, Map.of());

        public Match {
            variables = Map.copyOf(variables);
        }
    }

    /** Split a lookup path into segments: leading "/" dropped, trailing empty kept. */
    public static List<String> segmentsOf(String path) {
        Objects.requireNonNull(path, "path");
        String[] raw = path.split("/", -1);
        List<String> segments = new ArrayList<>();
        for (int i = 1; i < raw.length; i++) {
            segments.add(raw[i]);
        }
        return segments;
    }

    /** Parses a mapping pattern; rejects the shapes Spring rejects at startup. */
    public static RoutePattern parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        if (!spec.startsWith("/") && !spec.startsWith("**/")) {
            throw new IllegalArgumentException("pattern must start with '/' or '**/': " + spec);
        }
        List<String> parts = new ArrayList<>(List.of(spec.split("/", -1)));
        if (!parts.isEmpty() && parts.getFirst().isEmpty()) {
            parts.removeFirst(); // the empty element before a leading "/"
            // (a "**/…" pattern has no empty first element — its catch-all IS first)
        }
        List<Segment> segments = new ArrayList<>();
        int variableCount = 0;
        int stars = 0;
        int normalizedLength = 0;
        for (String part : parts) {
            if (part.equals("**")) {
                segments.add(new CatchAll(null));
                normalizedLength += 0;
            } else if (part.startsWith("{*") && part.endsWith("}")) {
                String name = part.substring(2, part.length() - 1);
                requireValidName(name, part);
                segments.add(new CatchAll(name));
                normalizedLength += 0;
            } else if (part.equals("*")) {
                segments.add(new WildcardStar());
                stars += 1;
                normalizedLength += 1;
            } else if (part.startsWith("{") && part.endsWith("}")) {
                Variable variable = parseVariable(part);
                segments.add(variable);
                variableCount += 1;
                normalizedLength += 1;
            } else if (part.isEmpty()) {
                throw new IllegalArgumentException("pattern has an empty segment: " + spec);
            } else {
                Literal literal = Literal.of(part);
                segments.add(literal);
                stars += literal.stars();
                normalizedLength += part.length();
            }
            normalizedLength += 1; // the segment's slot itself
        }
        validateCatchAllPlacement(segments, spec);
        return new RoutePattern(spec, List.copyOf(segments),
                variableCount + stars * 100, normalizedLength);
    }

    private final String spec;
    private final List<Segment> segments;
    private final int score;
    private final int normalizedLength;
    private final boolean catchAll;

    private RoutePattern(String spec, List<Segment> segments,
            int score, int normalizedLength) {
        this.spec = spec;
        this.segments = segments;
        this.score = score;
        this.normalizedLength = normalizedLength;
        this.catchAll = segments.getLast() instanceof CatchAll || segments.getFirst() instanceof CatchAll;
    }

    /** The pattern exactly as registered. */
    public String pattern() {
        return spec;
    }

    /** Specificity cost: 1 per captured variable, 100 per unbounded wildcard. */
    public int score() {
        return score;
    }

    /** Tiebreaker: literal characters + 1 per variable/wildcard element; catch-alls add 0. */
    public int normalizedLength() {
        return normalizedLength;
    }

    /** True when the pattern ends (or starts) with ** / {*var} — it swallows paths. */
    public boolean isCatchAll() {
        return catchAll;
    }

    /** Does this pattern match the already-split lookup path? Reports captures when it does. */
    public Match match(List<String> pathSegments) {
        Objects.requireNonNull(pathSegments, "pathSegments");
        boolean leadingCatchAll = segments.getFirst() instanceof CatchAll;
        boolean trailingCatchAll = segments.getLast() instanceof CatchAll;
        if (leadingCatchAll && trailingCatchAll && segments.size() > 1) {
            return matchSqueezed(pathSegments);
        }
        int fixed = segments.size();
        int minimumSegments = fixed - (trailingCatchAll ? 1 : 0) - (leadingCatchAll ? 1 : 0);
        int maximumSegments = trailingCatchAll || leadingCatchAll ? Integer.MAX_VALUE : fixed;
        if (pathSegments.size() < minimumSegments || pathSegments.size() > maximumSegments) {
            return Match.NONE;
        }
        Map<String, String> variables = new LinkedHashMap<>();
        int bodyStart = leadingCatchAll ? 1 : 0;
        int bodyEnd = segments.size() - (trailingCatchAll ? 1 : 0);
        int pathBodyStart = leadingCatchAll ? pathSegments.size() - (bodyEnd - bodyStart) : 0;
        for (int i = bodyStart; i < bodyEnd; i++) {
            if (!segments.get(i).matches(pathSegments.get(pathBodyStart + (i - bodyStart)),
                    variables)) {
                return Match.NONE;
            }
        }
        if (trailingCatchAll) {
            captureTail(segments.getLast(), pathSegments,
                    pathBodyStart + (bodyEnd - bodyStart), variables);
        }
        return new Match(true, variables);
    }

    /** Both ends are catch-alls: the fixed middle must fit somewhere inside the path. */
    private Match matchSqueezed(List<String> pathSegments) {
        int bodyStart = 1;
        int bodyEnd = segments.size() - 1;
        int body = bodyEnd - bodyStart;
        if (pathSegments.size() < body) {
            return Match.NONE;
        }
        for (int offset = 0; offset + body <= pathSegments.size(); offset++) {
            Map<String, String> variables = new LinkedHashMap<>();
            boolean all = true;
            for (int i = bodyStart; i < bodyEnd; i++) {
                if (!segments.get(i).matches(pathSegments.get(offset + (i - bodyStart)),
                        variables)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return new Match(true, variables);
            }
        }
        return Match.NONE;
    }

    private static void captureTail(Segment tail, List<String> pathSegments, int from,
            Map<String, String> variables) {
        if (tail instanceof CatchAll catchAll && catchAll.name() != null) {
            List<String> rest = pathSegments.subList(from, pathSegments.size());
            variables.put(catchAll.name(), "/" + String.join("/", rest));
        }
    }

    private static void validateCatchAllPlacement(List<Segment> segments, String spec) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i) instanceof CatchAll && i != 0 && i != segments.size() - 1) {
                throw new IllegalArgumentException(
                        "{*...} or ** pattern elements should be placed at the start or end of the pattern: "
                                + spec);
            }
        }
    }

    private static Variable parseVariable(String part) {
        // Brace-aware split on the first ':' at depth 1 — {id:\d{2}} keeps its regex intact.
        int colon = -1;
        int depth = 0;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ':' && depth == 1 && colon == -1) {
                colon = i;
            }
        }
        String name = colon == -1
                ? part.substring(1, part.length() - 1)
                : part.substring(1, colon);
        requireValidName(name, part);
        Pattern constraint = null;
        if (colon != -1) {
            String regex = part.substring(colon + 1, part.length() - 1);
            try {
                constraint = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("bad regex in " + part + ": " + e.getMessage(), e);
            }
        }
        return new Variable(name, constraint);
    }

    private static void requireValidName(String name, String part) {
        if (name == null || name.isBlank() || !name.chars().allMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("variable name must be alphanumeric: " + part);
        }
    }

    /* ---- segment model ---- */

    private sealed interface Segment permits CatchAll, Literal, Variable, WildcardStar {

        /** One path segment against this pattern segment; captures into variables. */
        boolean matches(String segment, Map<String, String> variables);
    }

    /** A ** or {*name} element: consumes zero or more segments (handled by the pattern). */
    private record CatchAll(String name) implements Segment {

        @Override
        public boolean matches(String segment, Map<String, String> variables) {
            return false; // placement logic lives in RoutePattern.match
        }
    }

    /** A {name} or {name:regex} element: exactly one segment, captured by name. */
    private record Variable(String name, Pattern constraint) implements Segment {

        @Override
        public boolean matches(String segment, Map<String, String> variables) {
            if (constraint != null && !constraint.matcher(segment).matches()) {
                return false;
            }
            variables.put(name, segment);
            return true;
        }
    }

    /** A bare * segment: any single segment, uncaptured. */
    private record WildcardStar() implements Segment {

        @Override
        public boolean matches(String segment, Map<String, String> variables) {
            return true;
        }
    }

    /** Literal text; ? = one char, * = any run, both inside the segment. */
    private record Literal(Pattern regex, int stars) implements Segment {

        static Literal of(String text) {
            StringBuilder expression = new StringBuilder();
            int stars = 0;
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '?' -> expression.append('.');
                    case '*' -> {
                        expression.append(".*");
                        stars++;
                    }
                    default -> {
                        if ("\\^.|[]{}()+-$".indexOf(c) >= 0) {
                            expression.append('\\');
                        }
                        expression.append(c);
                    }
                }
            }
            return new Literal(Pattern.compile(expression.toString()), stars);
        }

        @Override
        public boolean matches(String segment, Map<String, String> variables) {
            return regex.matcher(segment).matches();
        }
    }
}

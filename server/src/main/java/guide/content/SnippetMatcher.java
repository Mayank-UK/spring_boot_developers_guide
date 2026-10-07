package guide.content;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Snippet-source verification machinery — the invariant that teaching code is
 * quoted from real repo source: the code is the source of truth, never
 * invented. Ported faithfully from the express guide's snippet_sources.spec.
 *
 * Normalization contract (why snippets can still teach):
 * - Trailing line comments are stripped from BOTH snippet and source lines —
 *   snippets legitimately add teaching comments; we compare the code. The
 *   comment syntax is language-aware (// for java/ts/json/proto, -- for sql,
 *   # for yaml/properties, &lt;!-- --&gt; for xml) and quote-aware: a //
 *   inside 'https://…' is a string, not a comment.
 * - Blank lines are ignored on both sides.
 * - A snippet line that is ONLY comment syntax (e.g. `// …`, `# ...`)
 *   normalizes to empty and is therefore a GAP, never a match — that is what
 *   makes elided regions work: source lines may be skipped between matches.
 * - Every remaining snippet line must equal a source line exactly (after
 *   comment-strip + trim), IN ORDER. Intra-line elisions do NOT verify.
 */
public final class SnippetMatcher {

    /**
     * Languages whose snippets are code/config a repo file can own. The
     * express canon (ts, tsx, js, json, sql, proto, yaml, yml, dockerfile,
     * nginx, ini, graphql) plus this repo's own java/xml/properties. Terminal
     * interaction and prose (bash, sh, console, text, …) are exempt.
     */
    private static final Set<String> MUST_SOURCE_LANGUAGES = Set.of(
            "ts", "tsx", "js", "json", "sql", "proto",
            "yaml", "yml", "dockerfile", "nginx", "ini", "graphql",
            "java", "xml", "properties");

    /** Line-comment syntax per language family. */
    private static final Set<String> SLASH_COMMENTS =
            Set.of("ts", "tsx", "js", "jsx", "mjs", "cjs", "json", "proto", "java");
    private static final Set<String> DASH_COMMENTS = Set.of("sql");
    private static final Set<String> HASH_COMMENTS =
            Set.of("yaml", "yml", "dockerfile", "nginx", "ini", "graphql", "properties");
    private static final Set<String> XML_COMMENTS = Set.of("xml");

    private static final Pattern WINDOWS_PREFIX = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private SnippetMatcher() {
    }

    /** True when the language's snippets are code/config a repo file can own. */
    public static boolean isMustSourceLanguage(String language) {
        return language != null && MUST_SOURCE_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
    }

    /**
     * Remove line comments from one line, ignoring comment syntax inside string
     * literals (single/double/backtick, with backslash escapes). Also drops
     * same-line block comments for slash languages ({@code /* … *\/}) and xml
     * ({@code <!-- … -->}); an unterminated block comment runs to end of line.
     *
     * {@code --} (sql) and {@code #} (yaml-family) only start a comment at line
     * start or after whitespace, so TS {@code i--}, hex colors, or
     * {@code #field} never get mangled.
     */
    static String stripLineComments(String line, String language) {
        boolean slash = SLASH_COMMENTS.contains(language);
        boolean dash = DASH_COMMENTS.contains(language);
        boolean hash = HASH_COMMENTS.contains(language);
        boolean xml = XML_COMMENTS.contains(language);
        StringBuilder out = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (quote != 0) {
                out.append(ch);
                if (ch == '\\') {
                    out.append(next);
                    i += 1;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                out.append(ch);
                continue;
            }
            if (slash && ch == '/' && next == '/') {
                return out.toString();
            }
            if (slash && ch == '/' && next == '*') {
                int close = line.indexOf("*/", i + 2);
                if (close == -1) {
                    return out.toString();
                }
                i = close + 1;
                continue;
            }
            if (xml && ch == '<' && line.startsWith("<!--", i)) {
                int close = line.indexOf("-->", i + 4);
                if (close == -1) {
                    return out.toString();
                }
                i = close + 2;
                continue;
            }
            boolean atCommentStart = out.isEmpty()
                    || out.charAt(out.length() - 1) == ' '
                    || out.charAt(out.length() - 1) == '\t';
            if (dash && ch == '-' && next == '-' && atCommentStart) {
                return out.toString();
            }
            if (hash && ch == '#' && atCommentStart) {
                return out.toString();
            }
            out.append(ch);
        }
        return out.toString();
    }

    /** Strip the language's line comments, then trim. Empty ⇒ blank/gap line. */
    public static String normalizeSnippetLine(String line, String language) {
        return stripLineComments(line, language.toLowerCase(Locale.ROOT)).trim();
    }

    /** Result of one quote check: ok, or the first snippet line with no matching source line. */
    public record QuoteCheck(boolean ok, String mismatchedLine) {
        static final QuoteCheck MATCHED = new QuoteCheck(true, null);
    }

    /**
     * True when every normalized, non-empty snippet line appears in the
     * source's normalized lines IN ORDER (source lines may be skipped between
     * matches — that is what makes elisions and quote-fragments work).
     */
    public static QuoteCheck isQuotedFrom(String code, String sourceText, String language) {
        String lang = language.toLowerCase(Locale.ROOT);
        List<String> wanted = normalizeLines(code, lang);
        List<String> available = normalizeLines(sourceText, lang);
        int cursor = 0;
        for (String line : wanted) {
            while (cursor < available.size() && !available.get(cursor).equals(line)) {
                cursor += 1;
            }
            if (cursor == available.size()) {
                return new QuoteCheck(false, line);
            }
            cursor += 1;
        }
        return QuoteCheck.MATCHED;
    }

    private static List<String> normalizeLines(String text, String language) {
        return Arrays.stream(text.split("\n", -1))
                .map(line -> stripLineComments(line, language).trim())
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** Validate {@code source} path syntax; null when the path is acceptable. */
    public static String sourcePathError(String source) {
        if (source == null || source.isEmpty()) {
            return "source must be a non-empty repo-root-relative path";
        }
        if (source.startsWith("/") || WINDOWS_PREFIX.matcher(source).matches() || source.startsWith("~")) {
            return "source must be repo-root-relative, not absolute/home: \"" + source + "\"";
        }
        if (source.contains("\\")) {
            return "source must use forward slashes: \"" + source + "\"";
        }
        for (String segment : source.split("/", -1)) {
            if (segment.equals("..")) {
                return "source must not contain \"..\": \"" + source + "\"";
            }
            if (segment.isEmpty() || segment.equals(".")) {
                return "source must be a clean relative path (no // or . segments): \"" + source + "\"";
            }
        }
        return null;
    }
}

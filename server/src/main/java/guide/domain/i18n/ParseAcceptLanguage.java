package guide.domain.i18n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure Accept-Language parser — topic 27's teaching exhibit. RFC 4647's
 * grammar is small but the WILD inputs are not: browsers send q-values,
 * wildcards, mixed case, stray parameters, out-of-range weights, empty
 * segments and outright junk, and the parsing decision ("skip the bad
 * segment, keep the good ones, never throw") is exactly what a server owes
 * a hostile header. This class is deliberately framework-free (plain JDK,
 * no Spring imports) so the parser battery can pin its behavior without a
 * container, and so the negotiation algorithm it feeds stays inspectable.
 *
 * Grammar handled (one comma-separated list of segments):
 * <pre>
 * Accept-Language = 1#( language-range [ weight ] )
 * language-range  = 1*8ALPHA *( "-" 1*8alphanum )   |  "*"
 * weight          = OWS ";" OWS "q=" qvalue         ; qvalue in [0, 1]
 * </pre>
 *
 * Policy decisions, stated because implementations differ (see the topic's
 * negotiation section): tags are CASE-NORMALIZED (en, en-GB, zh-Hans);
 * a missing weight means q=1; a weight of 0 means "explicitly not
 * acceptable" and is kept here for the negotiator to exclude; DUPLICATE
 * normalized tags keep the first occurrence; any segment that fails the
 * grammar is SKIPPED and named in {@code skipped} — never an exception.
 */
public record ParseAcceptLanguage(
        List<Candidate> candidates,
        boolean headerPresent,
        List<String> skipped) {

    /** One parsed language range: normalized tag, its parts, its weight. */
    public record Candidate(
            String tag,
            String language,
            String region,
            double quality,
            boolean wildcard) {

        public Candidate {
            if (quality < 0 || quality > 1) {
                throw new IllegalArgumentException("quality must be within 0..1: " + quality);
            }
        }
    }

    /** language-range: 1-8 alpha, then hyphen-separated 1-8 alphanum subtags. */
    private static final Pattern TAG = Pattern.compile("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*");

    /** The one parameter the grammar defines: q=&lt;number&gt;. */
    private static final Pattern QUALITY = Pattern.compile("q=([0-9.]+)");

    public ParseAcceptLanguage {
        candidates = List.copyOf(candidates);
        skipped = List.copyOf(skipped);
    }

    /**
     * Parses a raw Accept-Language value. Null answers an absent header
     * (headerPresent false); a present-but-empty or all-junk header keeps
     * headerPresent true with zero candidates — the negotiator treats the
     * two cases differently (no preference versus no match).
     */
    public static ParseAcceptLanguage parse(String header) {
        if (header == null) {
            return new ParseAcceptLanguage(List.of(), false, List.of());
        }
        List<Candidate> candidates = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seenTags = new LinkedHashSet<>();
        String[] segments = header.split(",", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index].trim();
            String label = "segment " + (index + 1);
            if (segment.isEmpty()) {
                skipped.add(label + " \"\": empty segment");
                continue;
            }
            String[] parts = segment.split(";", -1);
            String rawTag = parts[0].trim();
            Double quality = parseQuality(parts, label, skipped);
            if (quality == null) {
                continue;
            }
            boolean wildcard = rawTag.equals("*");
            if (!wildcard && !TAG.matcher(rawTag).matches()) {
                skipped.add(label + " \"" + segment + "\": not a language tag");
                continue;
            }
            Candidate candidate = candidate(rawTag, wildcard, quality);
            // Duplicates keep the first occurrence: the header is an ordered
            // preference list, and "de, de" carries no new information.
            if (!seenTags.add(candidate.tag())) {
                skipped.add(label + " \"" + segment + "\": duplicate tag \"" + candidate.tag()
                        + "\" — first occurrence kept");
                continue;
            }
            candidates.add(candidate);
        }
        return new ParseAcceptLanguage(candidates, true, skipped);
    }

    /**
     * Candidates in serving order: quality descending, ties kept in header
     * order (a stable sort over the parsed order). This mirrors what the
     * servlet container's {@code request.getLocales()} hands the resolver.
     */
    public List<Candidate> byQuality() {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::quality).reversed())
                .toList();
    }

    // ---- internals ----

    /**
     * Quality from the segment's parameters: absent means 1.0. Returns null
     * (after recording why) when any parameter is not a well-formed q= —
     * including unrecognized parameters, unparseable numbers, and weights
     * outside 0..1.
     */
    private static Double parseQuality(String[] parts, String label, List<String> skipped) {
        Double quality = 1.0;
        boolean qualitySeen = false;
        for (int p = 1; p < parts.length; p++) {
            String param = parts[p].trim();
            if (param.isEmpty()) {
                skipped.add(label + " \"" + String.join(";", parts) + "\": empty parameter");
                return null;
            }
            Matcher q = QUALITY.matcher(param);
            if (qualitySeen || !q.matches()) {
                skipped.add(label + " \"" + String.join(";", parts)
                        + "\": unrecognized parameter (only q= is defined)");
                return null;
            }
            try {
                quality = Double.parseDouble(q.group(1));
            } catch (NumberFormatException notANumber) {
                skipped.add(label + " \"" + String.join(";", parts) + "\": quality is not a number");
                return null;
            }
            if (quality < 0 || quality > 1 || quality.isNaN() || quality.isInfinite()) {
                skipped.add(label + " \"" + String.join(";", parts) + "\": quality outside 0..1");
                return null;
            }
            qualitySeen = true;
        }
        return quality;
    }

    /** Case-normalizes a tag: en / en-GB / zh-Hans — language lowercase,
     * 2-letter subtags uppercase (region), 4-letter title-cased (script). */
    private static Candidate candidate(String rawTag, boolean wildcard, double quality) {
        if (wildcard) {
            return new Candidate("*", "", "", quality, true);
        }
        String[] subtags = rawTag.split("-");
        String language = subtags[0].toLowerCase(java.util.Locale.ROOT);
        String region = "";
        StringBuilder tag = new StringBuilder(language);
        for (int i = 1; i < subtags.length; i++) {
            String subtag = subtags[i];
            String normalized = switch (subtag.length()) {
                case 2 -> subtag.toUpperCase(java.util.Locale.ROOT);
                case 4 -> Character.toUpperCase(subtag.charAt(0))
                        + subtag.substring(1).toLowerCase(java.util.Locale.ROOT);
                default -> subtag.toLowerCase(java.util.Locale.ROOT);
            };
            if (i == 1 && subtag.length() == 2) {
                region = normalized;
            }
            tag.append('-').append(normalized);
        }
        return new Candidate(tag.toString(), language, region, quality, false);
    }
}

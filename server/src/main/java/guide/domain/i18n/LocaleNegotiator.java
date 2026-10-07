package guide.domain.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pure negotiation algorithm — which supported locale serves a parsed
 * Accept-Language. A deliberately small, documented, test-pinnable variant
 * of the lookup family Spring's AcceptHeaderLocaleResolver belongs to:
 *
 * <ol>
 *   <li>EXACT — for each candidate in quality order (ties in header order),
 *       each supported locale in list order: candidate tag equals supported
 *       tag exactly ("de" matches "de");</li>
 *   <li>LANGUAGE — for each candidate, each supported: candidate LANGUAGE
 *       equals supported language ("de-DE" matches "de" — a German speaker
 *       from Austria accepts the German bundle);</li>
 *   <li>DEFAULT — nothing matched: the configured default locale answers.
 *       NO_HEADER distinguishes an absent header from a present-but-matched-
 *       nothing one.</li>
 * </ol>
 *
 * The passes are strictly ordered here: the exact pass completes over ALL
 * candidates before the language pass starts. Spring's resolver interleaves
 * per preference (for EACH requested locale in order it takes an exact or
 * language match before considering the next), so on mixed headers the two
 * can disagree — "de-DE,fr;q=0.8" answers fr here (fr's exact match outranks
 * de-DE's language match) and de in Spring (de-DE is the FIRST preference,
 * and its language match wins before fr is consulted). The demo renders both
 * verdicts side by side precisely because the divergence is the lesson:
 * negotiation is a POLICY, pin your stack's behavior with a test.
 *
 * Two policy stances, stated because stacks differ: candidates with
 * quality 0 are EXCLUDED from matching (RFC 4647: "not acceptable"), and
 * the wildcard {@code *} matches NOTHING specifically — it signals "any
 * acceptable", which the default already expresses.
 */
public record LocaleNegotiator(List<String> supported, String defaultTag) {

    /** How the winner was chosen — NONE marks an unmatched score row. */
    public enum MatchKind {EXACT, LANGUAGE, DEFAULT, NO_HEADER, NONE}

    /** The full negotiation verdict: per-locale scores, the winner, why. */
    public record Negotiation(List<Scored> scores, String winner, MatchKind kind, String reason) {
    }

    /** One supported locale's best outcome against the candidate list. */
    public record Scored(String supported, double score, String matched, MatchKind kind) {
    }

    public LocaleNegotiator {
        supported = List.copyOf(supported);
    }

    /** Runs the two-pass lookup over the parsed header, producing the trace. */
    public Negotiation negotiate(ParseAcceptLanguage parsed) {
        List<ParseAcceptLanguage.Candidate> ranked = acceptable(parsed);
        for (ParseAcceptLanguage.Candidate candidate : ranked) {
            for (String tag : supported) {
                if (candidate.tag().equals(tag)) {
                    return negotiation(parsed, tag, MatchKind.EXACT,
                            "exact match on candidate " + candidate.tag()
                                    + " (q=" + candidate.quality() + ")");
                }
            }
        }
        for (ParseAcceptLanguage.Candidate candidate : ranked) {
            if (candidate.language().isEmpty()) {
                continue;
            }
            for (String tag : supported) {
                if (languageOf(tag).equals(candidate.language())) {
                    return negotiation(parsed, tag, MatchKind.LANGUAGE,
                            "language match: candidate " + candidate.tag()
                                    + " (q=" + candidate.quality() + ") serves " + tag);
                }
            }
        }
        if (!parsed.headerPresent()) {
            return negotiation(parsed, defaultTag, MatchKind.NO_HEADER,
                    "no Accept-Language header — the configured default answers");
        }
        return negotiation(parsed, defaultTag, MatchKind.DEFAULT,
                "no candidate matched a supported locale — the configured default answers");
    }

    /** Each supported locale's best score: the q of its best matching candidate. */
    private Negotiation negotiation(ParseAcceptLanguage parsed, String winner, MatchKind kind, String reason) {
        List<Scored> scores = new ArrayList<>();
        for (String tag : supported) {
            scores.add(best(tag, acceptable(parsed)));
        }
        return new Negotiation(scores, winner, kind, reason);
    }

    private static Scored best(String tag, List<ParseAcceptLanguage.Candidate> ranked) {
        for (ParseAcceptLanguage.Candidate candidate : ranked) {
            if (candidate.tag().equals(tag)) {
                return new Scored(tag, candidate.quality(), candidate.tag(), MatchKind.EXACT);
            }
        }
        for (ParseAcceptLanguage.Candidate candidate : ranked) {
            if (!candidate.language().isEmpty() && languageOf(tag).equals(candidate.language())) {
                return new Scored(tag, candidate.quality(), candidate.tag(), MatchKind.LANGUAGE);
            }
        }
        return new Scored(tag, 0, null, MatchKind.NONE);
    }

    /** Candidates in serving order with quality-0 ("not acceptable") removed. */
    private static List<ParseAcceptLanguage.Candidate> acceptable(ParseAcceptLanguage parsed) {
        return parsed.byQuality().stream()
                .filter(candidate -> candidate.quality() > 0)
                .toList();
    }

    private static String languageOf(String tag) {
        int hyphen = tag.indexOf('-');
        return hyphen < 0 ? tag : tag.substring(0, hyphen);
    }

    /** Locale tags are BCP 47: the language subtag is everything before '-'. */
    public static Locale toLocale(String tag) {
        return Locale.forLanguageTag(tag);
    }
}

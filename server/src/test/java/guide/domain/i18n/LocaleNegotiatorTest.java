package guide.domain.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The negotiation algorithm pinned: the two-pass lookup (exact tag, then
 * language-only, then default), q-ordering, the q=0 exclusion, and the
 * wildcard-matches-nothing stance — the behavior the resolver and the demo
 * both rely on.
 */
class LocaleNegotiatorTest {

    private static final List<String> SUPPORTED = List.of("en", "de", "fr", "ja");

    private final LocaleNegotiator negotiator = new LocaleNegotiator(SUPPORTED, "en");

    private LocaleNegotiator.Negotiation on(String header) {
        return negotiator.negotiate(ParseAcceptLanguage.parse(header));
    }

    @Test
    void anExactTagWinsExactly() {
        LocaleNegotiator.Negotiation result = on("de");
        assertEquals("de", result.winner());
        assertEquals(LocaleNegotiator.MatchKind.EXACT, result.kind());
        assertTrue(result.reason().contains("exact match"));
    }

    @Test
    void aRegionedCandidateServesItsLanguage() {
        LocaleNegotiator.Negotiation result = on("de-DE");
        assertEquals("de", result.winner());
        assertEquals(LocaleNegotiator.MatchKind.LANGUAGE, result.kind());
        assertTrue(result.reason().contains("de-DE"));
    }

    @Test
    void anExactMatchElsewhereBeatsThisCandidatesLanguageMatch() {
        // The EXACT pass completes over every candidate before the LANGUAGE
        // pass starts: fr (q=0.8, exact) wins over de-DE (q=1.0, language-only)
        // — mirroring Spring's resolver on the same header.
        LocaleNegotiator.Negotiation result = on("de-DE,fr;q=0.8");
        assertEquals("fr", result.winner());
        assertEquals(LocaleNegotiator.MatchKind.EXACT, result.kind());
    }

    @Test
    void higherQualityWinsAmongExactMatches() {
        assertEquals("fr", on("fr;q=0.9,de;q=0.3").winner());
    }

    @Test
    void anExactMatchBeatsAHigherQualityLanguageMatch() {
        // en is offered at q=0.3 and de only via de-AT at q=0.9 — but the
        // exact pass runs to completion before the language pass starts.
        assertEquals("en", on("en;q=0.3,de-AT;q=0.9").winner());
        assertEquals(LocaleNegotiator.MatchKind.EXACT, on("en;q=0.3,de-AT;q=0.9").kind());
    }

    @Test
    void qualityZeroMeansNotAcceptable() {
        assertEquals("en", on("de;q=0,en;q=0.5").winner());
    }

    @Test
    void theWildcardAloneMatchesNothingAndFallsToDefault() {
        LocaleNegotiator.Negotiation result = on("*;q=0.1");
        assertEquals("en", result.winner());
        assertEquals(LocaleNegotiator.MatchKind.DEFAULT, result.kind());
    }

    @Test
    void anAbsentHeaderIsNoHeaderNotADefaultMatch() {
        LocaleNegotiator.Negotiation result =
                negotiator.negotiate(ParseAcceptLanguage.parse(null));
        assertEquals("en", result.winner());
        assertEquals(LocaleNegotiator.MatchKind.NO_HEADER, result.kind());
    }

    @Test
    void unsupportedLanguagesFallToTheDefault() {
        LocaleNegotiator.Negotiation result = on("zh-CN,zh;q=0.8");
        assertEquals("en", result.winner());
        assertEquals(LocaleNegotiator.MatchKind.DEFAULT, result.kind());
    }

    @Test
    void theScoreMatrixNamesEachSupportedLocalesBestMatch() {
        List<LocaleNegotiator.Scored> scores = on("de-DE,en;q=0.4").scores();
        assertEquals(4, scores.size());
        LocaleNegotiator.Scored english = scores.getFirst();
        assertEquals("en", english.supported());
        assertEquals("en", english.matched());
        assertEquals(0.4, english.score());
        assertEquals(LocaleNegotiator.MatchKind.EXACT, english.kind());
        LocaleNegotiator.Scored german = scores.get(1);
        assertEquals("de", german.supported());
        assertEquals("de-DE", german.matched());
        assertEquals(1.0, german.score());
        assertEquals(LocaleNegotiator.MatchKind.LANGUAGE, german.kind());
        LocaleNegotiator.Scored french = scores.get(2);
        assertEquals(0, french.score());
        assertNull(french.matched());
        assertEquals(LocaleNegotiator.MatchKind.NONE, french.kind());
    }

    @Test
    void tiesKeepHeaderOrder() {
        assertEquals("fr", on("fr;q=0.8,de;q=0.8").winner());
        assertEquals("de", on("de;q=0.8,fr;q=0.8").winner());
    }
}

package guide.domain.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parser battery — the malformed-header traps pinned one by one.
 * Accept-Language is attacker-controlled input arriving on every request;
 * the contract under test is "skip the junk, name it, keep the good
 * segments, never throw".
 */
class ParseAcceptLanguageTest {

    @Test
    void parsesARealisticListWithQualitiesInHeaderOrder() {
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse("de-DE,fr;q=0.8,en;q=0.5");
        assertEquals(List.of("de-DE", "fr", "en"),
                parsed.candidates().stream().map(ParseAcceptLanguage.Candidate::tag).toList());
        assertEquals(List.of(1.0, 0.8, 0.5),
                parsed.candidates().stream().map(ParseAcceptLanguage.Candidate::quality).toList());
        assertTrue(parsed.skipped().isEmpty());
        assertTrue(parsed.headerPresent());
    }

    @Test
    void missingQualityMeansOne() {
        assertEquals(1.0, ParseAcceptLanguage.parse("fr").candidates().getFirst().quality());
    }

    @Test
    void theWildcardIsACandidate() {
        ParseAcceptLanguage.Candidate wildcard =
                ParseAcceptLanguage.parse("*;q=0.1").candidates().getFirst();
        assertTrue(wildcard.wildcard());
        assertEquals("*", wildcard.tag());
        assertEquals(0.1, wildcard.quality());
    }

    @Test
    void tagsAreCaseNormalizedLanguageLowercaseRegionUppercaseScriptTitled() {
        ParseAcceptLanguage.Candidate gb = ParseAcceptLanguage.parse("EN-gb").candidates().getFirst();
        assertEquals("en-GB", gb.tag());
        assertEquals("en", gb.language());
        assertEquals("GB", gb.region());
        ParseAcceptLanguage.Candidate hans = ParseAcceptLanguage.parse("ZH-hans").candidates().getFirst();
        assertEquals("zh-Hans", hans.tag());
    }

    @Test
    void malformedQualitySkipsOnlyItsSegment() {
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse("EN-gb;q=1.0, zz-ZZ;q=OOPS, fr");
        assertEquals(List.of("en-GB", "fr"),
                parsed.candidates().stream().map(ParseAcceptLanguage.Candidate::tag).toList());
        assertEquals(1, parsed.skipped().size());
        assertTrue(parsed.skipped().getFirst().contains("OOPS"));
    }

    @Test
    void qualitiesOutsideZeroToOneAreSkipped() {
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse("fr;q=1.5,de;q=2.5,en;q=-1,ja;q=0.5");
        assertEquals(List.of("ja"),
                parsed.candidates().stream().map(ParseAcceptLanguage.Candidate::tag).toList());
        assertEquals(3, parsed.skipped().size());
    }

    @Test
    void junkTagsAreSkipped() {
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse("%%%,de");
        assertEquals(List.of("de"),
                parsed.candidates().stream().map(ParseAcceptLanguage.Candidate::tag).toList());
        assertTrue(parsed.skipped().getFirst().contains("not a language tag"));
    }

    @Test
    void emptySegmentsAreSkipped() {
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse(",,de,");
        assertEquals(List.of("de"),
                parsed.candidates().stream().map(ParseAcceptLanguage.Candidate::tag).toList());
        assertEquals(3, parsed.skipped().size());
    }

    @Test
    void unknownParametersAreSkipped() {
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse("de;charset=utf-8");
        assertTrue(parsed.candidates().isEmpty());
        assertTrue(parsed.skipped().getFirst().contains("only q= is defined"));
    }

    @Test
    void duplicateTagsKeepTheFirstOccurrence() {
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse("fr;q=0.4,fr;q=0.9");
        assertEquals(1, parsed.candidates().size());
        assertEquals(0.4, parsed.candidates().getFirst().quality());
        assertTrue(parsed.skipped().getFirst().contains("duplicate tag \"fr\""));
    }

    @Test
    void nullHeaderIsAbsentBlankHeaderIsPresent() {
        assertFalse(ParseAcceptLanguage.parse(null).headerPresent());
        assertTrue(ParseAcceptLanguage.parse("").headerPresent());
        assertTrue(ParseAcceptLanguage.parse("   ").headerPresent());
        assertTrue(ParseAcceptLanguage.parse("").candidates().isEmpty());
    }

    @Test
    void aHostileBatteryNeverThrows() {
        List<String> hostile = List.of(
                ";;;;", "*-", "q=", "de;q=", "de;;q=1", "-", "*", ",,,,,",
                "de;q=0.00000000000000000000001", "a".repeat(100),
                "de-" + "x".repeat(9), "*", "de;Q=0.5", "de;q=+1", "de;q=1.", "de;q=.5");
        for (String header : hostile) {
            ParseAcceptLanguage parsed = ParseAcceptLanguage.parse(header);
            for (ParseAcceptLanguage.Candidate candidate : parsed.candidates()) {
                assertTrue(candidate.quality() >= 0 && candidate.quality() <= 1,
                        "quality must stay within 0..1 for header: " + header);
            }
        }
    }

    @Test
    void byQualityOrdersDescendingAndKeepsHeaderOrderOnTies() {
        List<ParseAcceptLanguage.Candidate> ranked =
                ParseAcceptLanguage.parse("en;q=0.5,fr;q=0.9,de").byQuality();
        assertEquals(List.of("de", "fr", "en"),
                ranked.stream().map(ParseAcceptLanguage.Candidate::tag).toList());
        List<ParseAcceptLanguage.Candidate> tied =
                ParseAcceptLanguage.parse("fr;q=0.8,de;q=0.8").byQuality();
        assertEquals(List.of("fr", "de"),
                tied.stream().map(ParseAcceptLanguage.Candidate::tag).toList());
    }
}

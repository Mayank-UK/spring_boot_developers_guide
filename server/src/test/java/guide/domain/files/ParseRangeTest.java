package guide.domain.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The ParseRange battery — every trap in RFC 9110 byte ranges as a table:
 * inclusive ends, EOF clamping vs EOF rejection (the mirror pair),
 * suffix semantics including the suffix-longer-than-file and suffix-zero
 * corners, reversed bounds, multi-range decline, wrong units, and garbage.
 * A wrong answer anywhere here is a corrupt download or a dead video
 * seek, which is why the parser is pure and this file is exhaustive.
 */
class ParseRangeTest {

    private static final long SIZE = 100;

    @Test
    void nullOrBlankHeaderIsAbsentNotUnsatisfiable() {
        assertEquals(HttpRange.Absent.INSTANCE, ParseRange.parse(null, SIZE));
        assertEquals(HttpRange.Absent.INSTANCE, ParseRange.parse("", SIZE));
        assertEquals(HttpRange.Absent.INSTANCE, ParseRange.parse("   ", SIZE));
    }

    @Test
    void firstByteOnly() {
        assertEquals(new HttpRange.Single(0, 0), ParseRange.parse("bytes=0-0", SIZE));
    }

    @Test
    void endBoundIsInclusiveNotExclusive() {
        // "0-99" asks for 100 bytes of a 100-byte file — the LAST byte is 99.
        HttpRange.Single range = (HttpRange.Single) ParseRange.parse("bytes=0-99", SIZE);
        assertEquals(0, range.start());
        assertEquals(99, range.endInclusive());
        assertEquals(100, range.length());
    }

    @Test
    void endPastEofIsClampedNotRejected() {
        // Resumable downloads depend on this leniency: a client with a stale
        // total size asks past the end and must receive the remainder, not 416.
        assertEquals(new HttpRange.Single(0, 99), ParseRange.parse("bytes=0-999999", SIZE));
        assertEquals(new HttpRange.Single(50, 99), ParseRange.parse("bytes=50-999999", SIZE));
    }

    @Test
    void openEndedRunsThroughTheFinalByte() {
        HttpRange.Single range = (HttpRange.Single) ParseRange.parse("bytes=40-", SIZE);
        assertEquals(40, range.start());
        assertEquals(99, range.endInclusive());
        assertEquals(60, range.length());
    }

    @Test
    void startAtEofIsUnsatisfiable() {
        // The mirror of the clamp: an END past EOF clamps, a START at-or-past
        // EOF addresses nothing and is 416 — the most mis-implemented line.
        assertUnsatisfiable("bytes=100-", SIZE);
        assertUnsatisfiable("bytes=100-200", SIZE);
        assertUnsatisfiable("bytes=999999-", SIZE);
    }

    @Test
    void suffixRangeMeansTheLastNBytes() {
        HttpRange.Single range = (HttpRange.Single) ParseRange.parse("bytes=-50", SIZE);
        assertEquals(50, range.start());
        assertEquals(99, range.endInclusive());
        assertEquals(50, range.length());
    }

    @Test
    void suffixLongerThanTheFileIsTheWholeFile() {
        assertEquals(new HttpRange.Single(0, 99), ParseRange.parse("bytes=-999999", SIZE));
        assertEquals(new HttpRange.Single(0, 99), ParseRange.parse("bytes=-100", SIZE));
    }

    @Test
    void suffixOfExactlyOneByte() {
        assertEquals(new HttpRange.Single(99, 99), ParseRange.parse("bytes=-1", SIZE));
    }

    @Test
    void suffixOfZeroAddressesNothing() {
        assertUnsatisfiable("bytes=-0", SIZE);
    }

    @Test
    void reversedBoundsAreUnsatisfiable() {
        assertUnsatisfiable("bytes=50-10", SIZE);
        assertUnsatisfiable("bytes=99-0", SIZE);
    }

    @Test
    void multiRangeListsAreDeclined() {
        // Serving multipart/byteranges is a legitimate implementation choice;
        // this endpoint declines the whole header instead of silently
        // answering only the first slice — 416, honestly documented.
        assertUnsatisfiable("bytes=0-1,5-6", SIZE);
        assertUnsatisfiable("bytes=0-9, 20-29", SIZE);
    }

    @Test
    void nonByteUnitsAreDeclined() {
        assertUnsatisfiable("items=0-5", SIZE);
        assertUnsatisfiable("rows=0-5", SIZE);
    }

    @Test
    void garbageSyntaxIsDeclined() {
        assertUnsatisfiable("bytes=abc", SIZE);
        assertUnsatisfiable("bytes=5", SIZE); // missing the dash entirely
        assertUnsatisfiable("bytes=-", SIZE); // bounds nothing
        assertUnsatisfiable("bytes=1-2-3", SIZE);
        assertUnsatisfiable("bytes=", SIZE);
        assertUnsatisfiable("byt", SIZE);
    }

    @Test
    void whitespaceAroundBoundsIsTolerated() {
        assertEquals(new HttpRange.Single(0, 9), ParseRange.parse("bytes= 0 - 9 ", SIZE));
        assertEquals(new HttpRange.Single(0, 9), ParseRange.parse("BYTES=0-9", SIZE));
    }

    @Test
    void emptyFileHasNoAddressableBytes() {
        assertUnsatisfiable("bytes=0-0", 0);
        assertUnsatisfiable("bytes=-5", 0);
    }

    @Test
    void negativeLookingBoundsAreDeclinedNotParsed() {
        // "-5-10" looks like a suffix glued to an end; the grammar has no such
        // production and the regex refuses it before any numeric parse.
        assertUnsatisfiable("bytes=-5-10", SIZE);
    }

    private static void assertUnsatisfiable(String header, long size) {
        HttpRange parsed = ParseRange.parse(header, size);
        assertEquals(HttpRange.Unsatisfiable.class, parsed.getClass(),
                "header \"" + header + "\" of size " + size + " must be unsatisfiable");
    }
}

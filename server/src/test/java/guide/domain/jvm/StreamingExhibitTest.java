package guide.domain.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * StreamingExhibit behavior across both taught charsets: byte consumers
 * count the payload exactly, the Reader decodes clean (zero replacement
 * characters, one arrow per chunk), and the naive per-chunk decode — with
 * boundaries forced inside multi-byte characters — is corrupted on every
 * run. All deterministic: no I/O, fixed buffers, fixed payload.
 */
class StreamingExhibitTest {

    private final StreamingExhibit exhibit = new StreamingExhibit();

    @Test
    void utf8ReaderIsCleanAndNaiveDecodeIsCorrupted() {
        StreamingExhibit.StreamingReport report = exhibit.run(16, "utf-8");

        assertEquals(16, report.chunks());
        assertEquals("utf-8", report.charset());
        assertEquals(16 * 9, report.payloadBytes(), "6 ASCII + 3-byte arrow per chunk");
        assertEquals(16 * 7, report.expectedCharCount());

        assertEquals(report.payloadBytes(), report.streamScan().totalBytes());
        assertEquals(report.payloadBytes(), report.channelScan().totalBytes());
        assertTrue(report.streamScan().readCalls() > 0);

        assertEquals(16 * 7, report.readerScan().totalChars());
        assertEquals(16, report.readerScan().multibyteChars());
        assertEquals(0, report.readerScan().replacementChars());

        assertTrue(report.naiveScan().replacementChars() > 0,
                "the chosen buffer forces a boundary inside an arrow");
        assertTrue(report.readerClean());
        assertTrue(report.naiveCorrupted());
        assertTrue(report.naiveScan().decodedSample().startsWith("c00000"));
    }

    @Test
    void utf16ReaderIsCleanAndNaiveDecodeIsCorrupted() {
        StreamingExhibit.StreamingReport report = exhibit.run(16, "utf-16");

        assertEquals("utf-16", report.charset());
        // UTF-16 writes a 2-byte BOM + 12 bytes of ASCII + 2-byte arrow per chunk.
        assertEquals(2 + 16 * 14, report.payloadBytes());

        assertEquals(report.payloadBytes(), report.streamScan().totalBytes());
        assertEquals(report.payloadBytes(), report.channelScan().totalBytes());

        // The decoder consumes the BOM; characters are the same as UTF-8's count.
        assertEquals(16 * 7, report.readerScan().totalChars());
        assertEquals(16, report.readerScan().multibyteChars());
        assertEquals(0, report.readerScan().replacementChars());

        assertTrue(report.naiveScan().replacementChars() > 0);
        assertTrue(report.readerClean());
        assertTrue(report.naiveCorrupted());
    }

    @Test
    void smallestChunkCountStillDemonstratesTheBug() {
        for (String charset : StreamingExhibit.SUPPORTED_CHARSETS) {
            StreamingExhibit.StreamingReport report = exhibit.run(StreamingExhibit.MIN_CHUNKS, charset);
            assertTrue(report.naiveCorrupted(), charset + " must corrupt at minimum chunks");
            assertTrue(report.readerClean(), charset + " reader must stay clean");
        }
    }

    @Test
    void largestChunkCountStillSumsExactly() {
        StreamingExhibit.StreamingReport report = exhibit.run(StreamingExhibit.MAX_CHUNKS, "utf-8");
        assertEquals(64 * 9, report.streamScan().totalBytes());
        assertEquals(64, report.readerScan().multibyteChars());
        assertEquals(0, report.readerScan().replacementChars());
    }
}

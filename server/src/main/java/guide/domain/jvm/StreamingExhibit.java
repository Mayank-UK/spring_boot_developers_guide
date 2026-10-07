package guide.domain.jvm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The live exhibit behind topic 02's jvm:streaming demo: ONE payload, read
 * four ways, with read boundaries forced INSIDE multi-byte characters — the
 * classic mojibake bug, demonstrated safely:
 * - InputStream: bytes in == bytes out; counting is safe, decoding is not
 *   yours to do per-chunk
 * - ReadableByteChannel: the same bytes through the NIO ByteBuffer API
 * - Reader (InputStreamReader): decodes ACROSS boundaries — zero replacement
 *   characters, because the decoder keeps partial characters between reads
 * - naive per-chunk new String(bytes): the bug — a boundary splits '→'
 *   (U+2192, 3 bytes in UTF-8 / 2 in UTF-16) and each half decodes to U+FFFD
 *
 * Pure and deterministic: the payload is built in memory, no filesystem, no
 * network; the trickle wrapper hands out a fixed number of bytes per read so
 * boundaries land where the lesson needs them — the exhibit measures the
 * damage rather than assuming it.
 */
public final class StreamingExhibit {

    public static final List<String> SUPPORTED_CHARSETS = List.of("utf-8", "utf-16");
    public static final int MIN_CHUNKS = 4;
    public static final int MAX_CHUNKS = 64;
    public static final int DEFAULT_CHUNKS = 16;

    /** U+2192 '→': three bytes in UTF-8, two in UTF-16 — never safe to split. */
    static final char MULTIBYTE = '\u2192';
    /** One chunk = "c" + 5-digit index (6 ASCII chars) + one '→'. */
    static final int ASCII_PER_CHUNK = 6;

    /** Raw byte census of one stream read. */
    public record ByteScan(int totalBytes, int readCalls) {
    }

    /** Decoded census of one Reader read. */
    public record ReaderScan(int totalChars, int multibyteChars, int replacementChars) {
    }

    /** The buggy strategy's result: a visible sample plus the damage count. */
    public record NaiveScan(String decodedSample, int replacementChars) {
    }

    /** The same bytes through the channel/ByteBuffer API. */
    public record ChannelScan(int totalBytes, int readCalls) {
    }

    /** Everything side by side: same payload, four consumers, one verdict. */
    public record StreamingReport(int chunks, String charset, int bufferSize, int payloadBytes,
            int expectedCharCount, ByteScan streamScan, ReaderScan readerScan, NaiveScan naiveScan,
            ChannelScan channelScan, boolean readerClean, boolean naiveCorrupted) {
    }

    public StreamingReport run(int chunks, String charsetName) {
        Charset charset = charset(charsetName);
        int bufferSize = bufferSizeFor(charset);
        byte[] payload = payload(chunks, charset);
        ByteScan streamScan = scanByStream(payload, bufferSize);
        ReaderScan readerScan = scanByReader(payload, charset, bufferSize);
        NaiveScan naiveScan = scanNaive(payload, charset, bufferSize);
        ChannelScan channelScan = scanByChannel(payload, bufferSize);
        return new StreamingReport(chunks, charset.name().toLowerCase(Locale.ROOT), bufferSize,
                payload.length, chunks * (ASCII_PER_CHUNK + 1), streamScan, readerScan, naiveScan,
                channelScan, readerScan.replacementChars() == 0, naiveScan.replacementChars() > 0);
    }

    private static Charset charset(String charsetName) {
        return switch (charsetName) {
            case "utf-8" -> StandardCharsets.UTF_8;
            case "utf-16" -> StandardCharsets.UTF_16;
            default -> throw new IllegalArgumentException("unsupported charset: " + charsetName);
        };
    }

    /**
     * Buffer sizes are CHOSEN, not arbitrary: 8 in UTF-8 and 15 in UTF-16 put
     * at least one read boundary inside the first '→', so the naive decode
     * corrupts on every run. (UTF-16 characters sit on even byte offsets —
     * an odd-sized buffer is what forces the split.)
     */
    private static int bufferSizeFor(Charset charset) {
        return charset == StandardCharsets.UTF_16 ? 15 : 8;
    }

    private static byte[] payload(int chunks, Charset charset) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < chunks; index++) {
            text.append('c').append(String.format("%05d", index)).append(MULTIBYTE);
        }
        return text.toString().getBytes(charset);
    }

    private static ByteScan scanByStream(byte[] payload, int bufferSize) {
        int totalBytes = 0;
        int readCalls = 0;
        try (InputStream in = new TrickleInputStream(payload, bufferSize)) {
            byte[] buffer = new byte[bufferSize];
            int read;
            while ((read = in.read(buffer)) != -1) {
                totalBytes += read;
                readCalls++;
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return new ByteScan(totalBytes, readCalls);
    }

    private static ReaderScan scanByReader(byte[] payload, Charset charset, int bufferSize) {
        int totalChars = 0;
        int multibyteChars = 0;
        int replacementChars = 0;
        try (Reader reader = new InputStreamReader(
                new TrickleInputStream(payload, bufferSize), charset)) {
            char[] buffer = new char[bufferSize];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int index = 0; index < read; index++) {
                    totalChars++;
                    if (buffer[index] == MULTIBYTE) {
                        multibyteChars++;
                    }
                    if (buffer[index] == '\uFFFD') {
                        replacementChars++;
                    }
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return new ReaderScan(totalChars, multibyteChars, replacementChars);
    }

    private static NaiveScan scanNaive(byte[] payload, Charset charset, int bufferSize) {
        StringBuilder decoded = new StringBuilder();
        int replacementChars = 0;
        try (InputStream in = new TrickleInputStream(payload, bufferSize)) {
            byte[] buffer = new byte[bufferSize];
            int read;
            while ((read = in.read(buffer)) != -1) {
                String piece = new String(buffer, 0, read, charset);
                replacementChars += countReplacements(piece);
                decoded.append(piece);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        String sample = decoded.substring(0, Math.min(24, decoded.length()));
        return new NaiveScan(sample, replacementChars);
    }

    private static ChannelScan scanByChannel(byte[] payload, int bufferSize) {
        int totalBytes = 0;
        int readCalls = 0;
        try (ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(payload))) {
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            int read;
            while ((read = channel.read(buffer)) != -1) {
                totalBytes += read;
                readCalls++;
                buffer.clear();
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return new ChannelScan(totalBytes, readCalls);
    }

    private static int countReplacements(String piece) {
        int count = 0;
        for (int index = 0; index < piece.length(); index++) {
            if (piece.charAt(index) == '\uFFFD') {
                count++;
            }
        }
        return count;
    }

    /**
     * The slow network: hands out at most {@code maxPerRead} bytes per read,
     * so buffer boundaries — not the in-memory array — decide where decoding
     * boundaries fall.
     */
    private static final class TrickleInputStream extends InputStream {

        private final byte[] data;
        private final int maxPerRead;
        private int position;

        TrickleInputStream(byte[] data, int maxPerRead) {
            this.data = data;
            this.maxPerRead = maxPerRead;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, maxPerRead), data.length - position);
            System.arraycopy(data, position, target, offset, count);
            position += count;
            return count;
        }
    }
}

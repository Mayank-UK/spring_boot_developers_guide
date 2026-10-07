package guide.domain.files;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Exhibit for the backpressure section: a GENERATED body — no file behind
 * it, just bytes computed as they are written — pumped to the socket in
 * fixed chunks, flushing after each one. Production of a chunk costs a
 * configurable pause ({@code delayMs} per chunk), so a reader can WATCH
 * the chunks arrive at the server's pace: the terminal companion pipes
 * this through a hex dumper and the bytes visibly tick in. The pattern is
 * a deterministic 251-byte cycle — every byte position is derivable, so a
 * client can verify any range it fetched received the right bytes.
 *
 * The write discipline is the lesson's other half: a fixed-size chunk
 * buffer, {@code write} then {@code flush}, never more produced than the
 * loop has handed off. The caller's OutputStream (ultimately the servlet
 * response stream) applies BACKPRESSURE by blocking the write when the
 * socket is full — the same drain discipline the express guide taught on
 * Node streams, arriving here as blocking I/O instead.
 */
public final class GeneratedBytePattern {

    /** One chunk = one flush; small enough that pacing is visible, large enough to be worth flushing. */
    public static final int CHUNK_BYTES = 4 * 1024;

    /** Defensive ceiling: a query parameter can ask for anything; this endpoint declines the absurd. */
    public static final int MAX_BYTES = 1024 * 1024;

    /** Per-chunk pause ceiling — a demo theater knob, not a production setting. */
    public static final int MAX_DELAY_MS = 250;

    private GeneratedBytePattern() {
    }

    /**
     * Writes exactly {@code totalBytes} of the deterministic pattern,
     * flushing per chunk and pausing {@code delayMs} after each when > 0.
     */
    public static void writeTo(OutputStream output, long totalBytes, int delayMs) throws IOException {
        if (totalBytes < 1 || totalBytes > MAX_BYTES) {
            throw new IllegalArgumentException("bytes must be between 1 and " + MAX_BYTES);
        }
        if (delayMs < 0 || delayMs > MAX_DELAY_MS) {
            throw new IllegalArgumentException("delayMs must be between 0 and " + MAX_DELAY_MS);
        }
        byte[] chunk = new byte[CHUNK_BYTES];
        long written = 0;
        while (written < totalBytes) {
            int filling = (int) Math.min(chunk.length, totalBytes - written);
            for (int i = 0; i < filling; i++) {
                chunk[i] = (byte) ((written + i) % 251);
            }
            output.write(chunk, 0, filling);
            output.flush();
            written += filling;
            if (delayMs > 0 && written < totalBytes) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException paused) {
                    Thread.currentThread().interrupt();
                    throw new IOException("generation interrupted", paused);
                }
            }
        }
    }
}

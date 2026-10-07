package guide.domain.reqres;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Exhibit for topic 09's streaming section: computed output written to a raw
 * OutputStream one chunk at a time, flushed as produced — the discipline a
 * StreamingResponseBody lambda must follow so the client sees each chunk the
 * moment it is computed instead of waiting for the last one. The controller
 * wraps this shape in Spring's StreamingResponseBody; the realtime topic
 * (28) builds the full push machinery (SseEmitter, WebSocket) on the same
 * flush discipline.
 */
public final class ChunkedLinesExhibit {

    /** Defensive ceiling: a query parameter can ask for a huge number — refuse it. */
    public static final int MAX_LINES = 200;

    private ChunkedLinesExhibit() {
    }

    /**
     * Writes {@code lines} numbered chunks, flushing after each one — the
     * whole point of streaming: hand every chunk to the socket as produced.
     */
    public static void writeLines(OutputStream output, int lines) throws IOException {
        if (lines < 1 || lines > MAX_LINES) {
            throw new IllegalArgumentException("lines must be between 1 and " + MAX_LINES);
        }
        for (int line = 1; line <= lines; line++) {
            output.write(("chunk " + line + " of " + lines + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}

package guide.domain.files;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * The measuring wrapper every upload drains through — three jobs in one
 * pass so the bytes are read EXACTLY ONCE:
 *
 * - COUNTS what flows (the size field of the FileRecord is observed, not
 *   assumed from any header);
 * - CAPTURES the first {@link SniffedContentType#HEAD_BYTES} bytes for
 *   content-type sniffing (the head has to be remembered while it is still
 *   in flight — rewinding a stream is not a thing);
 * - ENFORCES the size cap MID-STREAM: the moment more than {@code maxBytes}
 *   has flowed, {@link Exceeded} is thrown and the copy never reads another
 *   byte — a hostile 2 GB upload costs the server 10 MB of I/O, not 2 GB
 *   of anything.
 *
 * The digest is deliberately NOT here: the JDK's DigestInputStream wraps
 * this stream (see UploadFile), so the hash is computed by the platform
 * class while this class guards. Both run in the same single pass.
 */
public final class GuardedUploadStream extends FilterInputStream {

    /** Thrown mid-copy the instant the cap is crossed; carries the evidence for the 413 envelope. */
    public static final class Exceeded extends RuntimeException {

        private final long maxBytes;
        private final long receivedBytes;

        Exceeded(long maxBytes, long receivedBytes) {
            super("upload exceeded the " + maxBytes + "-byte cap at " + receivedBytes + " bytes");
            this.maxBytes = maxBytes;
            this.receivedBytes = receivedBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }

        public long receivedBytes() {
            return receivedBytes;
        }
    }

    private final long maxBytes;
    private final byte[] head = new byte[SniffedContentType.HEAD_BYTES];
    private int headFilled = 0;
    private long received = 0;

    public GuardedUploadStream(InputStream source, long maxBytes) {
        super(source);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value < 0) {
            return value;
        }
        onByte((byte) value);
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            for (int i = 0; i < read; i++) {
                onByte(buffer[offset + i]);
            }
        }
        return read;
    }

    private void onByte(byte b) {
        if (headFilled < head.length) {
            head[headFilled++] = b;
        }
        received++;
        if (received > maxBytes) {
            throw new Exceeded(maxBytes, received);
        }
    }

    /** The bytes actually copied — the honest size, set after the copy completes. */
    public long receivedBytes() {
        return received;
    }

    /** The captured leading bytes, right-sized; empty for a zero-byte upload. */
    public byte[] head() {
        return Arrays.copyOf(head, headFilled);
    }
}

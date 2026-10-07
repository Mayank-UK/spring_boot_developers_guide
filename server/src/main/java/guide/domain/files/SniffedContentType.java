package guide.domain.files;

/**
 * Content-type sniffing from leading bytes — pure, no I/O. The client's
 * Content-Type claim about an uploaded file is an ASSERTION, not evidence:
 * it is trivially spoofed, and the classic exploit is uploading an HTML or
 * SVG payload labeled image/png so a browser renders it inline in your
 * origin (stored XSS by content type). The rule this class embodies:
 *
 * - MAGIC BYTES WIN. A file that starts with PNG's signature IS a PNG as
 *   far as serving decisions go, whatever the form said.
 * - THE CLAIM IS BELIEVED ONLY WHEN THE BYTES CORROBORATE IT: the one
 *   fallback is text/plain, and only when the leading bytes are plausible
 *   text (no NUL, no strange controls) AND the claim said text/plain.
 * - EVERYTHING ELSE IS application/octet-stream — the inert type. Browsers
 *   download octet-stream instead of executing or rendering it, which is
 *   exactly the failure-safe default for unknown bytes.
 *
 * The table is deliberately short and honest: the formats this guide
 * round-trips (PNG, JPEG, GIF, WebP, PDF, ZIP-family). A production store
 * reaches for Apache Tika; the lesson is the DISCIPLINE, not the table
 * size — and even Tika's answer is still a guess from bytes, which is why
 * the serving side adds Content-Disposition and CSP on top.
 */
public final class SniffedContentType {

    /** How many leading bytes the sniffer needs; callers capture them while streaming anyway. */
    public static final int HEAD_BYTES = 32;

    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String GIF = "image/gif";
    public static final String WEBP = "image/webp";
    public static final String PDF = "application/pdf";
    public static final String ZIP = "application/zip";
    public static final String TEXT = "text/plain";
    public static final String BINARY = "application/octet-stream";

    private SniffedContentType() {
    }

    /**
     * The stored content type for an upload: magic bytes first, the
     * corroborated text/plain fallback second, inert octet-stream last.
     */
    public static String sniff(byte[] head, String claimedType) {
        if (startsWith(head, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) {
            return PNG;
        }
        if (startsWith(head, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return JPEG;
        }
        if (startsWith(head, "GIF8".getBytes())) {
            return GIF;
        }
        // RIFF container: bytes 0-3 "RIFF", bytes 8-11 identify WebP.
        if (startsWith(head, "RIFF".getBytes())
                && head.length >= 12 && startsAt(head, 8, "WEBP".getBytes())) {
            return WEBP;
        }
        if (startsWith(head, "%PDF".getBytes())) {
            return PDF;
        }
        if (startsWith(head, new byte[]{'P', 'K', 0x03, 0x04})) {
            return ZIP;
        }
        if (TEXT.equalsIgnoreCase(claimedType == null ? "" : claimedType.trim()) && looksLikeText(head)) {
            return TEXT;
        }
        return BINARY;
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        return startsAt(head, 0, signature);
    }

    private static boolean startsAt(byte[] head, int offset, byte[] signature) {
        if (head.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** Plausible text: non-empty, no NUL, and controls limited to tab/LF/CR/FF. */
    private static boolean looksLikeText(byte[] head) {
        if (head.length == 0) {
            return false;
        }
        for (byte b : head) {
            int unsigned = b & 0xFF;
            boolean benignControl = unsigned == '\t' || unsigned == '\n'
                    || unsigned == '\r' || unsigned == '\f';
            if (unsigned == 0 || (unsigned < 0x20 && !benignControl) || unsigned == 0x7F) {
                return false;
            }
        }
        return true;
    }
}

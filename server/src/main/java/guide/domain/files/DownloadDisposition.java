package guide.domain.files;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Content-Disposition header values, RFC 6266 — pure, unit-tested. Two
 * halves that must BOTH be right:
 *
 * - the DISPOSITION: "attachment" (browser saves; the safe default for
 *   anything untrusted) vs "inline" (browser renders; earned only when the
 *   content type is safe to render in your origin and the bytes are
 *   sniffed, not claimed);
 * - the FILENAME, in two spellings: the legacy {@code filename="…"}
 *   parameter carries an ASCII-ONLY fallback (quoted, with quote and
 *   backslash escaped), and {@code filename*=UTF-8''…} carries the real
 *   name, percent-encoded per RFC 5987 — which is how "résumé–final.png"
 *   survives to the user's Downloads folder with its accents intact.
 *   Browsers that understand filename* prefer it; the fallback exists for
 *   everything older.
 */
public final class DownloadDisposition {

    private DownloadDisposition() {
    }

    /** Builds the full header value, e.g. {@code attachment; filename="a.png"; filename*=UTF-8''a.png}. */
    public static String header(String disposition, String filename) {
        String fallback = asciiFallback(filename);
        return disposition
                + "; filename=\"" + fallback.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + "; filename*=UTF-8''" + encode(filename);
    }

    /** The ASCII-only fallback: non-ASCII dropped; "download" when nothing survives. */
    static String asciiFallback(String filename) {
        StringBuilder ascii = new StringBuilder(filename.length());
        for (int i = 0; i < filename.length(); i++) {
            char ch = filename.charAt(i);
            if (ch < 0x80 && !Character.isISOControl(ch)) {
                ascii.append(ch);
            }
        }
        return ascii.isEmpty() ? "download" : ascii.toString();
    }

    /** RFC 5987 encoding: UTF-8 bytes, percent-encoded unless attr-char (safe subset of ASCII). */
    static String encode(String filename) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = b & 0xFF;
            if (isAttrChar(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append(String.format(Locale.ROOT, "%%%02X", unsigned));
            }
        }
        return encoded.toString();
    }

    /** attr-char per RFC 5987: ALPHA / DIGIT / a small set of safe symbols — everything else is encoded. */
    private static boolean isAttrChar(int value) {
        boolean alphaNumeric = (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9');
        if (alphaNumeric) {
            return true;
        }
        return switch (value) {
            case '!', '#', '$', '&', '+', '-', '.', '^', '_', '`', '|', '~' -> true;
            default -> false;
        };
    }
}

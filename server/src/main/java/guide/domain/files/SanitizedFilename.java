package guide.domain.files;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The traversal defense for upload filenames — pure, no I/O, heavily tested.
 * The client's {@code filename} part is a string an attacker chooses freely,
 * so it is treated as hostile DISPLAY DATA, never as a path:
 *
 * 1. DECODE once (percent-encoded traversal — {@code ..%2F..%2F} — arrives
 *    as one opaque token; a decode-then-strip pass defuses both spellings
 *    with the same rule). A malformed escape sequence means the name was
 *    never really encoded — the literal is then stripped like any other.
 * 2. BASENAME-STRIP: keep only what follows the last '/' and the last '\'
 *    (both separators — Windows paths travel too). Any path components the
 *    client smuggled in are simply GONE.
 * 3. REJECT the residue when it is empty, ".", or ".." — a name that was
 *    nothing BUT traversal has no honest remainder to keep.
 * 4. STRIP control characters and cap the length at 200 — display data
 *    must be safe to log, to echo in Content-Disposition, and to show.
 *
 * The structural half of the defense lives elsewhere and is the stronger
 * half: the store NEVER derives a filesystem path from this name — bytes
 * land under a generated UUID id; the sanitized name is metadata only.
 */
public final class SanitizedFilename {

    /** Display names stay comfortably under filesystem/vheader limits. */
    public static final int MAX_LENGTH = 200;

    private SanitizedFilename() {
    }

    /** Sanitizes a client-supplied filename; empty when no honest name remains. */
    public static Optional<String> sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String decoded = decodeOnce(raw.trim());
        String basename = stripToBasename(decoded);
        if (basename.isBlank() || basename.equals(".") || basename.equals("..")) {
            return Optional.empty();
        }
        return Optional.of(stripControlChars(basename));
    }

    /** One percent-decode pass; malformed escapes mean "was never encoded" — literal survives. */
    private static String decodeOnce(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notActuallyEncoded) {
            return value;
        }
    }

    /** Keeps only the segment after the last separator of EITHER shape. */
    private static String stripToBasename(String value) {
        String current = value;
        int slash = current.lastIndexOf('/');
        if (slash >= 0) {
            current = current.substring(slash + 1);
        }
        int backslash = current.lastIndexOf('\\');
        if (backslash >= 0) {
            current = current.substring(backslash + 1);
        }
        return current.trim();
    }

    /** Drops ISO control chars (NUL, CR/LF — header/log injection), then caps the length. */
    private static String stripControlChars(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length() && cleaned.length() < MAX_LENGTH; i++) {
            char ch = value.charAt(i);
            if (!Character.isISOControl(ch)) {
                cleaned.append(ch);
            }
        }
        return cleaned.toString();
    }
}

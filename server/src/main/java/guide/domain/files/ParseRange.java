package guide.domain.files;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure Range parser — no I/O, no Spring, exhaustively unit-tested
 * because every trap in the format is an off-by-one:
 *
 * - byte positions are ZERO-based; the end bound is INCLUSIVE, so
 *   "bytes=0-99" asks for 100 bytes of a file whose last byte is 99;
 * - an end past EOF is LEGAL and CLAMPED ("bytes=0-999999" of a 100-byte
 *   file is "bytes=0-99") — that leniency is what makes resumed downloads
 *   work with a stale total size;
 * - a start at-or-past EOF is UNSATISFIABLE ("bytes=100-" of a 100-byte
 *   file addresses nothing) — the mirror image of the clamp, and the
 *   single most mis-implemented line in naive parsers;
 * - a SUFFIX range ("bytes=-50") means the LAST 50 bytes; a suffix longer
 *   than the file means the whole file; a suffix of ZERO ("bytes=-0")
 *   addresses nothing and is unsatisfiable;
 * - a reversed range (first greater than last) is unsatisfiable;
 * - this endpoint declines multi-range lists and non-byte units outright
 *   (416) — see {@link HttpRange.Unsatisfiable} for the honest alternative.
 */
public final class ParseRange {

    /** Exactly one "first-last" or "first-" or "-suffix" spec, with optional whitespace. */
    private static final Pattern SINGLE_SPEC =
            Pattern.compile("^bytes=\\s*(\\d*)\\s*-\\s*(\\d*)\\s*$",
                    Pattern.CASE_INSENSITIVE);

    private ParseRange() {
    }

    /**
     * Parses a Range header value against the file's size. A null/blank
     * header is {@link HttpRange.Absent}; anything else this endpoint will
     * not serve is {@link HttpRange.Unsatisfiable}.
     */
    public static HttpRange parse(String header, long size) {
        if (header == null || header.isBlank()) {
            return HttpRange.Absent.INSTANCE;
        }
        if (size <= 0) {
            return new HttpRange.Unsatisfiable(header); // no byte of an empty file is addressable
        }
        if (header.indexOf(',') >= 0) {
            return new HttpRange.Unsatisfiable(header); // multi-range: declined, not implemented
        }
        Matcher matcher = SINGLE_SPEC.matcher(header.trim());
        if (!matcher.matches()) {
            return new HttpRange.Unsatisfiable(header); // wrong unit, garbage, or missing bounds
        }
        String firstText = matcher.group(1);
        String lastText = matcher.group(2);
        if (firstText.isEmpty() && lastText.isEmpty()) {
            return new HttpRange.Unsatisfiable(header); // "bytes=-" bounds nothing
        }
        if (firstText.isEmpty()) {
            return suffixRange(Long.parseLong(lastText), size, header);
        }
        long start = Long.parseLong(firstText);
        if (start >= size) {
            return new HttpRange.Unsatisfiable(header); // past EOF — NOT clamped (see class doc)
        }
        long lastInclusive = lastText.isEmpty()
                ? size - 1 // open-ended: through the final byte
                : Math.min(Long.parseLong(lastText), size - 1); // over-long end is clamped
        if (start > lastInclusive) {
            return new HttpRange.Unsatisfiable(header); // reversed (or end clamped below start)
        }
        return new HttpRange.Single(start, lastInclusive);
    }

    private static HttpRange suffixRange(long suffixLength, long size, String header) {
        if (suffixLength <= 0) {
            return new HttpRange.Unsatisfiable(header); // "bytes=-0" addresses nothing
        }
        // A suffix at least as long as the file addresses the whole file.
        long start = Math.max(0, size - suffixLength);
        return new HttpRange.Single(start, size - 1);
    }
}

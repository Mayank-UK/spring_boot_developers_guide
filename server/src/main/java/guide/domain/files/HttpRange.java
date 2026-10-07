package guide.domain.files;

/**
 * The result of parsing one Range header against one file's size — the
 * three-way answer every range-aware download endpoint needs:
 *
 * - {@link Single}: a satisfiable 0..size-1 slice → 206 + Content-Range.
 * - {@link Absent}: no usable Range header → the ordinary 200 full body.
 * - {@link Unsatisfiable}: a header this endpoint declines — semantically
 *   impossible ranges (start past EOF, reversed bounds, suffix length 0)
 *   AND, deliberately, everything this endpoint does not serve (multi-range
 *   lists, other units, malformed syntax) → 416 with a star-form
 *   Content-Range naming the total size. RFC 9110 permits IGNORING
 *   nonsense ranges and answering 200; a teaching endpoint chooses to
 *   answer 416 so the refusal is visible instead of silently swallowed.
 */
public sealed interface HttpRange {

    /** A satisfiable byte slice; BOTH bounds are inclusive and already clamped to the file. */
    record Single(long start, long endInclusive) implements HttpRange {

        /** The exact number of body bytes this slice will produce (end inclusive — the classic off-by-one). */
        public long length() {
            return endInclusive - start + 1;
        }
    }

    /** No Range header (or blank) — serve the full representation. */
    record Absent() implements HttpRange {

        public static final Absent INSTANCE = new Absent();
    }

    /** The header is declined — 416, with a "bytes star-slash size" Content-Range to say why. */
    record Unsatisfiable(String header) implements HttpRange {

        /** The unsatisfiable answer's Content-Range value prefix; the caller appends the size. */
        public static final String CONTENT_RANGE_PREFIX = "bytes */";
    }
}

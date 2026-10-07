package guide.domain.mongo;

/** One row of the locale histogram: a BCP-47-style tag and its visit count. */
public record LocaleTally(String locale, long visits) {

    public LocaleTally {
        if (locale == null || locale.isBlank()) {
            throw new IllegalArgumentException("tally locale must be a non-blank string");
        }
        if (visits < 1) {
            throw new IllegalArgumentException("a histogram bucket only exists once visited");
        }
    }
}

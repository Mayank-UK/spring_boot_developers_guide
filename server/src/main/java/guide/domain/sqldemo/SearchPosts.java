package guide.domain.sqldemo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.sqldemo.PostSearch.SearchReport;

/**
 * Topic 17's search use case (demo db:search): validate the query term the
 * way every public search box must be (non-blank, length-capped — the
 * length cap is about not shipping a megabyte of text to the lexing
 * machinery, not about safety: the term binds as a parameter either way),
 * then hand it to the port. Mode selection lives here too: 'fts' (default)
 * emphasizes the ranked hits, 'like' runs the substring variant — the report
 * always carries BOTH sides plus the plans, because the lesson is the
 * comparison, not the winner.
 */
public final class SearchPosts {

    /** Searches longer than this are refused before any SQL is built. */
    private static final int MAX_QUERY = 200;

    /** Hard ceiling on returned hits — a demo search page, not a data export. */
    private static final int MAX_LIMIT = 10;

    private final PostSearch search;

    public SearchPosts(PostSearch search) {
        this.search = search;
    }

    /** SearchReport for the term; blank or oversized terms are validation failures. */
    public SearchReport run(String q, Integer limit) {
        if (q == null || q.isBlank()) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "q is required — a search needs a term (try q=pagination or q=\"uniform interface\")"));
        }
        if (q.length() > MAX_QUERY) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "q must be at most " + MAX_QUERY + " characters"));
        }
        int bounded = limit == null ? 5 : limit;
        if (bounded < 1 || bounded > MAX_LIMIT) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "limit must be between 1 and " + MAX_LIMIT));
        }
        return search.search(q, bounded);
    }
}

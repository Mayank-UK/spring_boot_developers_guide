package guide.domain.sqldemo;

import java.util.List;

/**
 * The search PORT (topic 17, demo db:search): full-text search over posts
 * through the V12 tsvector column, with the LIKE-based alternative measured
 * beside it so the two styles — relevance-ranked lexeme matching vs substring
 * scan — can be compared on results AND on query plans in one response.
 */
public interface PostSearch {

    /** One ranked hit: which post, how well it matched, and a headline excerpt with hits marked. */
    record SearchHit(String id, String title, double rank, String excerpt) {
    }

    /**
     * The full comparison report: ranked FTS hits for the query, how many
     * rows the LIKE variant found, and the plans the planner chose for both
     * (plus the FTS plan with sequential scans disabled — the honest way to
     * SHOW the GIN index path exists even at 12 rows, where the planner is
     * right to ignore it).
     */
    record SearchReport(String q, int limit, List<SearchHit> hits, long likeCount,
            List<String> ftsPlan, List<String> ftsPlanIndexForced, List<String> likePlan,
            String comparisonNote) {

        public SearchReport {
            hits = List.copyOf(hits);
            ftsPlan = List.copyOf(ftsPlan);
            ftsPlanIndexForced = List.copyOf(ftsPlanIndexForced);
            likePlan = List.copyOf(likePlan);
        }
    }

    /** Ranked FTS hits plus the LIKE count and all three plan readouts. */
    SearchReport search(String q, int limit);
}

package guide.data.sqldemo;

import guide.domain.sqldemo.PostSearch;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The search demo's PostgreSQL adapter: one term run through BOTH search
 * styles, plus three plan readouts — because the FTS-vs-LIKE lesson is only
 * complete when you can read what the PLANNER did, not just what came back.
 *
 * - FTS: websearch_to_tsquery parses the term the way a search box means it
 *   (quoted phrases, OR, -exclusions), the @@ operator matches it against
 *   the V12-generated search_vector, ts_rank orders by relevance, and
 *   ts_headline marks the hits inline. Ranking weights live in the
 *   migration (title 'A', body 'B') — DDL, not query-time magic.
 * - LIKE: the same term as a substring, ILIKE '%…%' — which can use NO
 *   index (a LEADING wildcard has no prefix to seek), so its plan is a
 *   sequential scan by construction, at any table size.
 * - Plans: EXPLAIN for both, plus the FTS plan with enable_seqscan forced
 *   off inside a transaction — the honest way to SHOW the GIN bitmap path
 *   at 12 rows, where the default plan is (correctly!) a seq scan. The
 *   SET LOCAL is transaction-scoped on purpose and rolled back: a pooled
 *   connection must never leak planner settings to its next borrower.
 *
 * Every user-supplied byte binds as a parameter — the '%' wildcards for the
 * LIKE variant are concatenated AROUND the placeholder in SQL text, never
 * into it.
 */
public final class PgPostSearch implements PostSearch {

    private static final String FTS_SQL = """
            SELECT p.id::text AS id,
                   p.title,
                   round(ts_rank(p.search_vector, q.query)::numeric, 6) AS rank,
                   ts_headline('english', p.body, q.query, 'MaxWords=20, MinWords=10, StartSel=<b>, StopSel=</b>') AS excerpt
            FROM posts p, websearch_to_tsquery('english', CAST(? AS text)) AS q(query)
            WHERE p.search_vector @@ q.query
            ORDER BY rank DESC, p.created_at DESC
            LIMIT ?
            """;

    private static final String LIKE_COUNT_SQL = """
            SELECT count(*)
            FROM posts
            WHERE title ILIKE '%' || CAST(? AS text) || '%'
               OR body ILIKE '%' || CAST(? AS text) || '%'""";

    private static final String FTS_EXPLAIN_SQL = """
            EXPLAIN SELECT p.id FROM posts p, websearch_to_tsquery('english', CAST(? AS text)) AS q(query)
            WHERE p.search_vector @@ q.query""";

    private static final String LIKE_EXPLAIN_SQL = """
            EXPLAIN SELECT count(*) FROM posts
            WHERE title ILIKE '%' || CAST(? AS text) || '%'
               OR body ILIKE '%' || CAST(? AS text) || '%'""";

    private static final int MAX_PLAN_LINES = 4;

    private final DbDemoActivation activation;
    private final JdbcTemplate jdbc;

    public PgPostSearch(DbDemoActivation activation, JdbcTemplate jdbc) {
        this.activation = Objects.requireNonNull(activation, "activation");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public SearchReport search(String q, int limit) {
        activation.ensureReady();
        List<SearchHit> hits = jdbc.query(FTS_SQL, (rs, rowNum) -> new SearchHit(
                rs.getString("id"),
                rs.getString("title"),
                rs.getBigDecimal("rank").doubleValue(),
                rs.getString("excerpt")),
                q, limit);
        Long likeCount = jdbc.queryForObject(LIKE_COUNT_SQL, Long.class, q, q);
        return new SearchReport(q, limit, hits, likeCount == null ? 0 : likeCount,
                explain(FTS_EXPLAIN_SQL, q),
                explainForcedIndex(FTS_EXPLAIN_SQL, q),
                explain(LIKE_EXPLAIN_SQL, q, q),
                comparisonNote(hits.size(), likeCount == null ? 0 : likeCount));
    }

    // ---- the plan readouts ----

    /** EXPLAIN, default planner settings — what the database would really do. */
    private List<String> explain(String explainSql, Object... boundTerm) {
        return jdbc.query(explainSql, (rs, rowNum) -> rs.getString(1), boundTerm)
                .stream().limit(MAX_PLAN_LINES).toList();
    }

    /**
     * EXPLAIN with sequential scans disabled, inside a transaction so the
     * SET LOCAL dies with the rollback — the planner-setting containment
     * rule every shared pool demands.
     */
    private List<String> explainForcedIndex(String explainSql, String term) {
        return jdbc.execute((Connection con) -> collectForcedPlan(con, explainSql, term));
    }

    private List<String> collectForcedPlan(Connection con, String explainSql, String term)
            throws SQLException {
        boolean originalAutoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try (Statement set = con.createStatement()) {
            set.execute("SET LOCAL enable_seqscan = off");
            List<String> lines = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(explainSql)) {
                ps.setString(1, term);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && lines.size() < MAX_PLAN_LINES) {
                        lines.add(rs.getString(1));
                    }
                }
            }
            return lines;
        } finally {
            con.rollback();
            con.setAutoCommit(originalAutoCommit);
        }
    }

    private static String comparisonNote(int ftsHits, long likeHits) {
        return ftsHits + " ranked hit(s) via FTS lexemes and stemming vs " + likeHits
                + " substring hit(s) via ILIKE: the two answer different questions — try q=errors, "
                + "where FTS matches the post titled \"One error envelope…\" (both sides stem to "
                + "'error') while ILIKE '%errors%' matches nothing, because the text says 'error'. "
                + "At 12 rows the planner seq-scans BOTH (it is right to); the forced plan shows "
                + "the GIN bitmap path that wins as the table grows.";
    }
}

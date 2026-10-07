package guide.data.schema;

import guide.domain.schema.KeyStrategyTables;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL KeyStrategyTables ADAPTER — the experiment apparatus over the
 * two V3 lab tables. The identifiers these queries cannot bind as
 * parameters (TRUNCATE targets, FROM lists) come from a TWO-ENTRY WHITELIST:
 * the port's own table constants. Anything else is rejected as an
 * programmer error before a query is built — the defensive rule for
 * identifiers is the mirror of the bind-parameter rule for values: when the
 * database cannot parameterize it, the string must already be a constant you
 * chose, never a string you were handed.
 *
 * ctid parsing lives here (page, offset are PostgreSQL's storage vocabulary,
 * not the domain's): "(3,41)" becomes page 3, tuple 41, and heapSequence
 * 3*1_000_000+41 — one monotonic number where "earlier inserted" reliably
 * means smaller, given a freshly truncated table. That is the comparison's
 * ground truth: no clock, no DEFAULT, just where the storage engine put it.
 */
public final class PgKeyStrategyTables implements KeyStrategyTables {

    private static final Set<String> LAB_TABLES = Set.of(V4_TABLE, V7_TABLE);

    private static final String TRUNCATE_BOTH_SQL =
            "TRUNCATE TABLE " + V4_TABLE + ", " + V7_TABLE;

    private static final String PK_INDEX_BYTES_SQL = """
            SELECT pg_catalog.pg_relation_size(ix.indexrelid)
            FROM pg_catalog.pg_index ix
            JOIN pg_catalog.pg_class tbl ON tbl.oid = ix.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace
            WHERE n.nspname = 'public' AND tbl.relname = ? AND ix.indisprimary
            """;

    private final JdbcTemplate jdbc;
    private final LiveDbArea liveDbArea;

    public PgKeyStrategyTables(JdbcTemplate jdbc, LiveDbArea liveDbArea) {
        this.jdbc = jdbc;
        this.liveDbArea = liveDbArea;
    }

    @Override
    public void load(String table, List<UUID> ids) {
        liveDbArea.ensureActivated();
        String target = requireLabTable(table);
        jdbc.update("TRUNCATE TABLE " + target);
        List<Object[]> batch = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            batch.add(new Object[] {id});
        }
        jdbc.batchUpdate("INSERT INTO " + target + " (id) VALUES (?)", batch);
    }

    @Override
    public long primaryKeyIndexBytes(String table) {
        liveDbArea.ensureActivated();
        // Qualified by the whitelist check above; the name itself binds.
        Long bytes = jdbc.queryForObject(PK_INDEX_BYTES_SQL, Long.class, table);
        return bytes == null ? 0L : bytes;
    }

    @Override
    public List<KeyedRow> rowsInKeyOrder(String table) {
        liveDbArea.ensureActivated();
        String target = requireLabTable(table);
        return jdbc.query("SELECT id, ctid::text AS ctid, created_at FROM " + target + " ORDER BY id",
                (rs, rowNum) -> new KeyedRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("ctid"),
                        heapSequence(rs.getString("ctid")),
                        rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public void clear() {
        liveDbArea.ensureActivated();
        jdbc.update(TRUNCATE_BOTH_SQL);
    }

    /** "(page,offset)" -> page * 1_000_000 + offset: one monotonic heap position. */
    private static long heapSequence(String ctid) {
        String stripped = ctid.replaceAll("[()\\s]", "");
        int comma = stripped.indexOf(',');
        if (comma < 1 || comma == stripped.length() - 1) {
            throw new IllegalStateException("unparseable ctid: " + ctid);
        }
        return Long.parseLong(stripped, 0, comma, 10) * 1_000_000L
                + Long.parseLong(stripped, comma + 1, stripped.length(), 10);
    }

    private static String requireLabTable(String table) {
        if (!LAB_TABLES.contains(table)) {
            throw new IllegalArgumentException(
                    "not a key-strategy lab table: " + table + " (expected one of " + LAB_TABLES + ")");
        }
        return table;
    }
}

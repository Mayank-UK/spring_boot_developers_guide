package guide.data.schema;

import guide.domain.schema.ColumnInsight;
import guide.domain.schema.ConstraintInsight;
import guide.domain.schema.IndexInsight;
import guide.domain.schema.SchemaCatalog;
import guide.domain.schema.TableInsight;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PostgreSQL SchemaCatalog ADAPTER — and the topic's teaching artifact: the
 * schema explorer does not ship a hand-written description of the schema; it
 * ASKS POSTGRESQL, through the same catalogs psql's \d and every IDE read.
 *
 * Catalog discipline, all deliberate:
 *
 * - information_schema is the SQL-standard view layer (tables, columns);
 *   pg_catalog is PostgreSQL's native one (constraints, indexes, sizes,
 *   comments). Each fact is read from wherever it is FIRST-CLASS: standard
 *   views where they exist, pg_catalog where PostgreSQL-specific truth
 *   (format_type, pg_get_constraintdef, pg_total_relation_size) lives.
 *
 * - IDENTIFIERS NEVER INTERPOLATE. Table names arrive from the wire, so they
 *   are validated against ^[a-z_][a-z0-9_]*$ and passed as BIND PARAMETERS —
 *   to_regclass(?) is the one place a name enters a query, and it answers
 *   NULL for anything unknown rather than erroring. The dynamic bits the
 *   bind layer cannot express (none here) would be whitelisted constants.
 *
 * - System schemas are excluded by construction (pg_namespace.nspname =
 *   'public'), which is what makes the 404 honest: an unknown name and a
 *   PostgreSQL internal name are the same fact — not one of ours.
 */
public final class PgSchemaCatalog implements SchemaCatalog {

    /** User-supplied table names must look like identifiers — nothing else enters a query. */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private static final String TABLES_SQL = """
            SELECT c.relname AS table_name
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'r'
            ORDER BY c.relname
            """;

    private static final String TABLE_FACTS_SQL = """
            SELECT pg_catalog.obj_description(r.oid, 'pg_class') AS table_comment,
                   CASE WHEN r.reltuples >= 0 THEN r.reltuples END AS row_estimate
            FROM pg_catalog.pg_class r
            WHERE r.oid = pg_catalog.to_regclass(?)
            """;

    private static final String COLUMNS_SQL = """
            SELECT a.attname AS column_name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                   NOT a.attnotnull AS nullable,
                   pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) AS column_default,
                   pg_catalog.col_description(a.attrelid, a.attnum) AS column_comment
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_attrdef ad
                   ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
            WHERE a.attrelid = pg_catalog.to_regclass(?)
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

    private static final String CONSTRAINTS_SQL = """
            SELECT con.conname AS constraint_name,
                   CASE con.contype
                       WHEN 'p' THEN 'PRIMARY KEY'
                       WHEN 'f' THEN 'FOREIGN KEY'
                       WHEN 'u' THEN 'UNIQUE'
                       WHEN 'c' THEN 'CHECK'
                       ELSE con.contype::text END AS kind,
                   pg_catalog.pg_get_constraintdef(con.oid, true) AS definition
            FROM pg_catalog.pg_constraint con
            WHERE con.conrelid = pg_catalog.to_regclass(?)
            ORDER BY con.conname
            """;

    private static final String INDEXES_SQL = """
            SELECT idx.relname AS index_name,
                   pg_catalog.pg_get_indexdef(idx.oid) AS definition,
                   pg_catalog.pg_relation_size(idx.oid) AS index_bytes,
                   pg_catalog.pg_size_pretty(pg_catalog.pg_relation_size(idx.oid)) AS index_size,
                   (SELECT string_agg(attr.attname, ', ' ORDER BY k.ord)
                      FROM unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_catalog.pg_attribute attr
                        ON attr.attrelid = ix.indrelid AND attr.attnum = k.attnum) AS key_columns
            FROM pg_catalog.pg_index ix
            JOIN pg_catalog.pg_class idx ON idx.oid = ix.indexrelid
            WHERE ix.indrelid = pg_catalog.to_regclass(?)
            ORDER BY idx.relname
            """;

    private static final String TABLE_SIZE_SQL = """
            SELECT pg_catalog.pg_total_relation_size(pg_catalog.to_regclass(?)) AS total_bytes,
                   pg_catalog.pg_size_pretty(
                       pg_catalog.pg_total_relation_size(pg_catalog.to_regclass(?))) AS total_size
            """;

    private final JdbcTemplate jdbc;
    private final LiveDbArea liveDbArea;

    public PgSchemaCatalog(JdbcTemplate jdbc, LiveDbArea liveDbArea) {
        this.jdbc = jdbc;
        this.liveDbArea = liveDbArea;
    }

    @Override
    public List<String> tableNames() {
        liveDbArea.ensureActivated();
        return jdbc.queryForList(TABLES_SQL, String.class);
    }

    @Override
    public Optional<TableInsight> table(String name) {
        liveDbArea.ensureActivated();
        if (name == null || !SAFE_TABLE_NAME.matcher(name).matches()) {
            // A hostile or malformed name describes no table — absent, not an error.
            return Optional.empty();
        }
        String qualified = "public." + name.toLowerCase(Locale.ROOT);

        List<TableFacts> facts = jdbc.query(TABLE_FACTS_SQL, TABLE_FACTS_MAPPER, qualified);
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        TableFacts table = facts.getFirst();

        List<ColumnInsight> columns = jdbc.query(COLUMNS_SQL, (rs, rowNum) -> new ColumnInsight(
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getBoolean("nullable"),
                rs.getString("column_default"),
                rs.getString("column_comment")), qualified);

        List<ConstraintInsight> constraints = jdbc.query(CONSTRAINTS_SQL, (rs, rowNum) ->
                new ConstraintInsight(
                        rs.getString("constraint_name"),
                        rs.getString("kind"),
                        rs.getString("definition")), qualified);

        List<IndexInsight> indexes = jdbc.query(INDEXES_SQL, (rs, rowNum) -> new IndexInsight(
                rs.getString("index_name"),
                rs.getString("key_columns"),
                rs.getString("definition"),
                rs.getLong("index_bytes"),
                rs.getString("index_size")), qualified);

        Map<String, Object> size = jdbc.queryForMap(TABLE_SIZE_SQL, qualified, qualified);

        return Optional.of(new TableInsight(name, table.comment(), table.rowEstimate(),
                ((Number) size.get("total_bytes")).longValue(),
                String.valueOf(size.get("total_size")),
                columns, constraints, indexes));
    }

    /** obj_description + reltuples in one row; rowEstimate null when never analyzed. */
    private record TableFacts(String comment, Long rowEstimate) {
    }

    private static final RowMapper<TableFacts> TABLE_FACTS_MAPPER =
            (rs, rowNum) -> {
                java.math.BigDecimal estimate = rs.getBigDecimal("row_estimate");
                return new TableFacts(rs.getString("table_comment"),
                        estimate == null ? null : Long.valueOf(estimate.longValue()));
            };
}

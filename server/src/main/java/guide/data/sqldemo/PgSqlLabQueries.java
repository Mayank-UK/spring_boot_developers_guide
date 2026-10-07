package guide.data.sqldemo;

import guide.domain.sqldemo.LabQueryName;
import guide.domain.sqldemo.SqlLabQueries;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SQL lab's PostgreSQL adapter: four named queries as plain text blocks
 * over V11's lab_staff dataset, each returned WITH its SQL so the reader
 * sees the query beside its rows. There is no client-SQL mode — the map from
 * {@code ?query=cte} to a constant IS the whitelist (the alternative, an
 * endpoint that runs whatever text arrives, is the injection demo's
 * punchline, not a feature).
 *
 * Deterministic by contract: V11 seeds twelve fixed rows, so these results
 * are the same on every fresh database — which is what lets the tests pin
 * exact numbers (platform payroll 53,900,000 cents; Knuth hired 140 days
 * after Adele Goldberg) rather than loose shapes.
 */
public final class PgSqlLabQueries implements SqlLabQueries {

    private static final String CTE_SQL = """
            WITH RECURSIVE org_chart (depth, id, name, manager_id, path) AS (
                SELECT 0, id, name, manager_id, name
                FROM lab_staff
                WHERE id = 'staff-002'
              UNION ALL
                SELECT o.depth + 1, s.id, s.name, s.manager_id, o.path || ' > ' || s.name
                FROM lab_staff s
                JOIN org_chart o ON s.manager_id = o.id
            )
            SELECT depth, id, name, path
            FROM org_chart
            ORDER BY depth, name
            """;

    private static final String WINDOW_SQL = """
            SELECT name,
                   department,
                   salary_cents,
                   RANK()       OVER (PARTITION BY department ORDER BY salary_cents DESC) AS salary_rank,
                   ROW_NUMBER() OVER (PARTITION BY department ORDER BY hired_on)           AS hire_seq,
                   SUM(salary_cents) OVER (PARTITION BY department ORDER BY hired_on)      AS running_total_cents
            FROM lab_staff
            ORDER BY department, hired_on""";

    private static final String LAG_SQL = """
            SELECT department,
                   name,
                   to_char(hired_on, 'YYYY-MM-DD') AS hired_on,
                   to_char(lag(hired_on) OVER hire_order, 'YYYY-MM-DD') AS previous_hire_on,
                   coalesce(hired_on - lag(hired_on) OVER hire_order, 0) AS days_since_previous_hire
            FROM lab_staff
            WINDOW hire_order AS (PARTITION BY department ORDER BY hired_on)
            ORDER BY department, hired_on
            """;

    private static final String AGGREGATE_SQL = """
            SELECT dept.department,
                   dept.headcount,
                   dept.total_salary_cents,
                   mgr.name AS manager_name
            FROM (
                SELECT department,
                       count(*)        AS headcount,
                       sum(salary_cents) AS total_salary_cents
                FROM lab_staff
                GROUP BY department
            ) dept
            LEFT JOIN lab_staff mgr
              ON mgr.department = dept.department
             AND mgr.id IN (
                 SELECT manager_id FROM lab_staff
                 WHERE department = dept.department AND manager_id IS NOT NULL
             )
            ORDER BY dept.department""";

    private final DbDemoActivation activation;
    private final JdbcTemplate jdbc;

    public PgSqlLabQueries(DbDemoActivation activation, JdbcTemplate jdbc) {
        this.activation = Objects.requireNonNull(activation, "activation");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public LabResult run(LabQueryName name) {
        activation.ensureReady();
        String sql = switch (name) {
            case CTE -> CTE_SQL;
            case WINDOW -> WINDOW_SQL;
            case LAG -> LAG_SQL;
            case AGGREGATE -> AGGREGATE_SQL;
        };
        // queryForList: each row a column-name -> value map, order preserved —
        // the JSON the client renders IS the psql table, transposed.
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        return new LabResult(name, name.title(), name.description(), sql, rows);
    }
}

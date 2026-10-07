package guide.data.sqldemo;

import guide.domain.sqldemo.SqlSafetySandbox;
import guide.domain.sqldemo.SqlSafetySandbox.LookupOutcome;
import guide.domain.sqldemo.SqlSafetySandbox.SandboxRow;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The injection sandbox's PostgreSQL adapter — one lookup written twice, the
 * whole lesson in one class.
 *
 * THE SANDBOX TABLE is demo state, not schema: created idempotently here (IF
 * NOT EXISTS + ON CONFLICT seed) the way the posts seed replays, because it
 * is a disposable demo fixture the demo owns end to end — migrations carry
 * the app's real schema, and a fake table of fake secrets is not that.
 *
 * THE BLAST-RADIUS CAP is the part worth copying: the VULNERABLE path really
 * does concatenate the payload into SQL text, but the statement executes on
 * a dedicated connection authenticated as demo_sandbox — a role whose ONLY
 * grant is SELECT on demo_injection. A UNION escape that names posts (or
 * flyway_schema_history, or anything else) dies at "permission denied for
 * table", proving the deeper lesson: bind parameters are the FIX, least
 * privilege is the BACKSTOP — sanitize all you like, still assume the bug
 * ships, and let the database account's grants decide what a bug can reach.
 */
public final class PgSqlSafetySandbox implements SqlSafetySandbox {

    private static final String SANDBOX_ROLE = "demo_sandbox";
    private static final String SANDBOX_PASSWORD = "demo_sandbox_pw";

    private static final String ENSURE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS demo_injection (
                username text PRIMARY KEY,
                role     text NOT NULL,
                secret   text NOT NULL
            )""";

    private static final String SEED_SQL = """
            INSERT INTO demo_injection (username, role, secret) VALUES
                ('ada',     'admin',  'flag{ada-reads-everything}'),
                ('bravo',   'editor', 'flag{bravo-edits-the-sandbox}'),
                ('charlie', 'viewer', 'flag{charlie-views-only}')
            ON CONFLICT (username) DO NOTHING""";

    private static final String ENSURE_ROLE_SQL = """
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                    CREATE ROLE %s LOGIN PASSWORD '%s';
                END IF;
            END $$""".formatted(SANDBOX_ROLE, SANDBOX_ROLE, SANDBOX_PASSWORD);

    private static final String GRANT_SQL = "GRANT USAGE ON SCHEMA public TO " + SANDBOX_ROLE
            + "; GRANT SELECT ON demo_injection TO " + SANDBOX_ROLE;

    private static final String PARAMETERIZED_SQL =
            "SELECT username, role, secret FROM demo_injection WHERE username = ?";

    private static final String VULNERABLE_PREFIX =
            "SELECT username, role, secret FROM demo_injection WHERE username = '";

    private static final RowMapper<SandboxRow> ROW = (rs, rowNum) -> new SandboxRow(
            rs.getString("username"), rs.getString("role"), rs.getString("secret"));

    private final DbDemoActivation activation;
    private final JdbcTemplate jdbc;
    private final DataSource mainDataSource;

    private final Object sandboxMonitor = new Object();
    private volatile JdbcTemplate restrictedJdbc;

    public PgSqlSafetySandbox(DbDemoActivation activation, JdbcTemplate jdbc, DataSource mainDataSource) {
        this.activation = Objects.requireNonNull(activation, "activation");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mainDataSource = Objects.requireNonNull(mainDataSource, "mainDataSource");
    }

    @Override
    public LookupOutcome lookupParameterized(String payload) {
        ensureSandboxReady();
        List<SandboxRow> rows = jdbc.query(PARAMETERIZED_SQL, ROW, payload);
        return new LookupOutcome(PARAMETERIZED_SQL, rows,
                rows.isEmpty()
                        ? "the payload traveled as a VALUE — compared against every username, matched "
                                + "none, and the statement's structure stayed exactly what was written"
                        : rows.size() + " row(s) matched the payload as a plain value (a username that "
                                + "literally spells the payload — nothing structural happened)",
                null);
    }

    @Override
    public LookupOutcome lookupVulnerable(String payload) {
        ensureSandboxReady();
        // teaching-exception: deliberately vulnerable — REAL string concatenation
        // into SQL text, so the attack is real. The ONLY containment is the
        // connection's role (demo_sandbox: SELECT on demo_injection and nothing
        // else) plus this method returning a fixed read-only column set. Never
        // write this line in production code; read it here, then don't.
        String vulnerableSql = VULNERABLE_PREFIX + payload + "'";
        try {
            List<SandboxRow> rows = restricted().query(vulnerableSql, ROW);
            return new LookupOutcome(vulnerableSql, rows,
                    rows.isEmpty()
                            ? "concatenated, but this payload still parsed as a comparison that "
                                    + "matched nothing (structure changed; luck decided the result)"
                            : "the payload became SQL STRUCTURE — the WHERE clause now ends with an "
                                    + "always-true branch, so every sandbox row (secrets included) "
                                    + "came back. Input decided the query's shape.",
                    null);
        } catch (DataAccessException escape) {
            String message = String.valueOf(escape.getMostSpecificCause().getMessage());
            boolean denied = message.contains("permission denied");
            return new LookupOutcome(vulnerableSql, List.of(),
                    denied
                            ? "the payload tried to leave the sandbox (a UNION or similar structural "
                                    + "escape) — the restricted role stopped it at the table boundary"
                            : "the concatenated text was not even valid SQL — a syntax error, which "
                                    + "is the BEST case a concat bug can hope for",
                    denied ? message : null);
        }
    }

    // ---- the sandbox and its one-grant role ----

    /** Table + role once; the SEED replays on every lookup (self-healing, idempotent). */
    private void ensureSandboxReady() {
        activation.ensureReady();
        if (restrictedJdbc == null) {
            synchronized (sandboxMonitor) {
                if (restrictedJdbc == null) {
                    jdbc.execute(ENSURE_TABLE_SQL);
                    jdbc.execute(ENSURE_ROLE_SQL);
                    jdbc.execute(GRANT_SQL);
                    // The restricted connection: same server, one-table credentials.
                    restrictedJdbc = new JdbcTemplate(
                            new DriverManagerDataSource(mainUrl(), SANDBOX_ROLE, SANDBOX_PASSWORD));
                }
            }
        }
        jdbc.update(SEED_SQL);
    }

    /** The live JDBC URL, read off a real connection — never reconstructed from config guesses. */
    private String mainUrl() {
        try (java.sql.Connection connection = mainDataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (java.sql.SQLException unreachable) {
            throw new IllegalStateException("could not read the datasource URL for the sandbox role",
                    unreachable);
        }
    }

    private JdbcTemplate restricted() {
        JdbcTemplate restricted = restrictedJdbc;
        if (restricted == null) {
            throw new IllegalStateException("sandbox not initialized");
        }
        return restricted;
    }
}

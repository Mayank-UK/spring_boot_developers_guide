package guide.data.sqldemo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.sql.SchemaMigrator;
import guide.domain.sqldemo.MigrationCatalog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The migrations demo's PostgreSQL adapter: the ledger read straight off
 * flyway_schema_history (the table Flyway itself maintains — reading it is
 * the honest way to show "the database remembers what was applied"), and
 * the apply delegated to SchemaMigrator, the app's one programmatic Flyway
 * seam. No Flyway API objects leak through this class: the port speaks in
 * plain rows and counts.
 */
public final class PgMigrationCatalog implements MigrationCatalog {

    /** Why this demo reads history the way operators do: the ledger table, plain SQL. */
    private static final String HISTORY_SQL = """
            SELECT version, description, installed_on, success
            FROM flyway_schema_history
            WHERE version IS NOT NULL
            ORDER BY installed_rank""";

    /** The mode line every response carries — this repo's non-default, deliberate. */
    private static final String MODE = "programmatic (spring.flyway.enabled=false — SchemaMigrator, not autoconfig)";

    private final DbDemoActivation activation;
    private final JdbcTemplate jdbc;
    private final SchemaMigrator migrator;

    public PgMigrationCatalog(DbDemoActivation activation, JdbcTemplate jdbc, SchemaMigrator migrator) {
        this.activation = Objects.requireNonNull(activation, "activation");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.migrator = Objects.requireNonNull(migrator, "migrator");
    }

    @Override
    public History history() {
        activation.ensureReady();
        List<AppliedMigration> applied = jdbc.query(HISTORY_SQL, (rs, rowNum) -> new AppliedMigration(
                rs.getString("version"),
                rs.getString("description"),
                rs.getObject("installed_on", OffsetDateTime.class),
                rs.getBoolean("success")));
        return new History(applied, summary(), 0, MODE);
    }

    @Override
    public Application applyPending() {
        activation.ensureReady();
        Result<SchemaMigrator.MigrationSummary> result = migrator.migrate();
        if (result instanceof Failed<SchemaMigrator.MigrationSummary> failed) {
            throw AppException.of(failed.failure());
        }
        SchemaMigrator.MigrationSummary summary =
                ((Success<SchemaMigrator.MigrationSummary>) result).value();
        return new Application(summary.migrationsApplied(), summary.appliedVersions(), summary(),
                summary.migrationsApplied() == 0
                        ? "idempotent by construction: flyway_schema_history already lists every "
                                + "migration on the classpath, so migrate() executed zero scripts — "
                                + "a no-op that cost one history query"
                        : "applied " + summary.migrationsApplied() + " pending migration(s): "
                                + String.join(", ", summary.appliedVersions()));
    }

    /** The one-sentence state, rebuilt fresh on every call (SchemaMigrator's own summary). */
    private String summary() {
        return switch (migrator.freshInfo()) {
            case Success<String> ok -> ok.value();
            case Failed<String> failed -> throw AppException.of(failed.failure());
        };
    }
}

package guide.data.sql;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The programmatic migrations SEAM: everything the app does with Flyway,
 * behind three methods a test (or a live-data activation) can call
 * deliberately. Boot's Flyway autoconfiguration is OFF by design
 * (spring.flyway.enabled=false in application.yml) — migrations here run when
 * the data area ACTIVATES, not when the process starts, because a schema
 * check must never gate application boot (the degrade-gracefully rule: the
 * app serves its in-memory surfaces with PostgreSQL down; the live flip is
 * what pays the schema bill, lazily, once).
 *
 * Idempotent by construction: Flyway's schema-history table records applied
 * versions, so migrate() on an up-to-date database is a no-op that costs one
 * history query — the property that lets the lazy activation call it on
 * EVERY first-live-use without knowing whether it is first.
 *
 * Failures are VALUES, never crashes: an unreachable PostgreSQL classifies as
 * the UNAVAILABLE kind (the taxonomy's "a dependency is down" — 503 on the
 * wire), while anything else (a broken migration script is OUR bug) stays
 * INTERNAL. Classification walks the cause chain for the JDBC connection
 * exception family — SQLSTATE class 08 is the SQL-standard "connection
 * exception" — plus the TCP-level refusals, using only java.sql types so the
 * postgresql driver can stay runtime-scoped.
 */
public final class SchemaMigrator {

    private static final String LOCATION = "classpath:db/migration";

    private final DataSource dataSource;

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** What one migrate() call did — the shape tests and the router read. */
    public record MigrationSummary(int migrationsApplied, List<String> appliedVersions) {

        public MigrationSummary {
            appliedVersions = List.copyOf(appliedVersions);
        }
    }

    /** Brings the schema up to date; a Failed Result (never a throw) when the dependency or a script fails. */
    public Result<MigrationSummary> migrate() {
        try {
            MigrateResult result = flyway().migrate();
            List<String> versions = result.migrations == null
                    ? List.of()
                    : result.migrations.stream()
                            .map(migration -> migration.version)
                            .filter(Objects::nonNull)
                            .toList();
            return Result.success(new MigrationSummary(result.migrationsExecuted, versions));
        } catch (RuntimeException failure) {
            return Result.failed(unavailableOrInternal(
                    "migrations could not run against the configured database", failure));
        }
    }

    /** Every applied version string, oldest first (["1"] after a fresh V1 run). */
    public Result<List<String>> appliedVersions() {
        try {
            List<String> versions = Arrays.stream(flyway().info().applied())
                    .map(MigrationInfo::getVersion)
                    .filter(Objects::nonNull)
                    .map(MigrationVersion::getVersion)
                    .toList();
            return Result.success(versions);
        } catch (RuntimeException failure) {
            return Result.failed(unavailableOrInternal(
                    "the migration history could not be read", failure));
        }
    }

    /**
     * A fresh, human-readable state summary ("1 applied, 0 pending: 1"),
     * rebuilt from a new info() on every call — the sentence a live-data
     * activation (and topic 17's migration demo) can log or serve as-is.
     */
    public Result<String> freshInfo() {
        try {
            MigrationInfo[] all = flyway().info().all();
            long applied = Arrays.stream(all)
                    .filter(info -> info.getState().isApplied())
                    .count();
            long pending = all.length - applied;
            return Result.success(applied + " applied, " + pending + " pending: "
                    + (all.length == 0 ? "no migrations found" : all[all.length - 1].getVersion()));
        } catch (RuntimeException failure) {
            return Result.failed(unavailableOrInternal(
                    "the migration state could not be summarized", failure));
        }
    }

    /** One Flyway instance per call — cheap, and never cached across a container restart. */
    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .load();
    }

    /**
     * The classifier: dependency-down (UNAVAILABLE, 503) versus our-bug
     * (INTERNAL, 500). A broken migration script must not masquerade as an
     * outage — the two kinds get very different operational responses.
     */
    private static Failure unavailableOrInternal(String context, RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                return unavailable(context, sql);
            }
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException) {
                return unavailable(context, cause);
            }
            if (cause.getMessage() != null
                    && cause.getMessage().contains("Unable to obtain connection")) {
                return unavailable(context, cause);
            }
        }
        return Failure.of(FailureKind.INTERNAL,
                context + ": " + failure.getMessage(),
                java.util.Map.of("cause", String.valueOf(failure.getClass().getSimpleName())));
    }

    private static Failure unavailable(String context, Throwable cause) {
        return Failure.of(FailureKind.UNAVAILABLE,
                context + ": PostgreSQL is unreachable (" + cause.getMessage() + ")",
                java.util.Map.of("dependency", "postgres"));
    }
}

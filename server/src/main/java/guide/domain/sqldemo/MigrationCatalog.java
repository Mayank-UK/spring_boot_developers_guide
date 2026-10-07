package guide.domain.sqldemo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The migrations PORT (topic 17, demo db:migrations): what this app's
 * PROGRAMMATIC Flyway seam exposes to the surface — the applied history, the
 * pending count, and the idempotent apply that SchemaMigrator (the data-side
 * class this port fronts) executes. Deliberately NOT a generic Flyway
 * wrapper: this port can read history and apply pending migrations, nothing
 * else — repair, clean, and validate stay operator-side, because a demo
 * endpoint that could drop migration history would be a loaded lesson.
 */
public interface MigrationCatalog {

    /** One applied row of flyway_schema_history — the ledger migrations leave behind. */
    record AppliedMigration(String version, String description,
            OffsetDateTime installedOn, boolean success) {
    }

    /** The full state: applied rows oldest-first, the one-sentence summary, how many are pending. */
    record History(List<AppliedMigration> applied, String summary, int pending, String mode) {

        public History {
            applied = List.copyOf(applied);
        }
    }

    /** What one deliberate apply did — zero is the honest, common answer. */
    record Application(int migrationsApplied, List<String> appliedVersions, String summary, String note) {

        public Application {
            appliedVersions = List.copyOf(appliedVersions);
        }
    }

    /** The ledger as it stands (activating the db area if this is the first call). */
    History history();

    /** Runs SchemaMigrator.migrate() — idempotent by Flyway's own schema history. */
    Application applyPending();
}

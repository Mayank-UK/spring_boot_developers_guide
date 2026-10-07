package guide.domain.sqldemo;

import guide.domain.sqldemo.MigrationCatalog.Application;
import guide.domain.sqldemo.MigrationCatalog.History;

/**
 * Topic 17's migrations exhibit (demo db:migrations): the thin use case over
 * the MigrationCatalog port — GET shows the ledger, POST applies pending and
 * proves the second call is a no-op. No input to validate (the port takes
 * none on purpose), so this class is the naming seam that keeps controllers
 * talking to use cases rather than ports directly.
 */
public final class DbMigrationsExhibit {

    private final MigrationCatalog catalog;

    public DbMigrationsExhibit(MigrationCatalog catalog) {
        this.catalog = catalog;
    }

    /** The applied ledger plus the state sentence — what GET /migrations serves. */
    public History history() {
        return catalog.history();
    }

    /** One deliberate, idempotent migrate — what POST /migrations/apply runs. */
    public Application applyPending() {
        return catalog.applyPending();
    }
}

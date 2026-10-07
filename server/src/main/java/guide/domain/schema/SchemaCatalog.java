package guide.domain.schema;

import java.util.List;
import java.util.Optional;

/**
 * The schema-insight PORT: what "look at the database's own description of
 * itself" means in the domain's language. No information_schema, no
 * pg_catalog, no JdbcTemplate in sight — those are PostgreSQL adapter
 * concerns (guide.data.schema.PgSchemaCatalog); up here the catalog answers
 * questions about tables, and the use cases decide what is a 404.
 *
 * AREA is this port's ScenarioStore area ('db'): unlike the posts area
 * ('api', fake by default and routed at call time), the schema demos have no
 * in-memory counterpart — they are ABOUT the real database — so the area
 * starts unactivated and LiveDbArea flips it LIVE programmatically once
 * migrations have actually run (the programmatic activation seam).
 */
public interface SchemaCatalog {

    /** The ScenarioStore area the schema demos activate (the 'db' area). */
    String AREA = "db";

    /**
     * Every user table in the public schema, name-sorted — including
     * flyway_schema_history, which is as much one of this guide's tables as
     * posts is (the migrations section teaches through it).
     */
    List<String> tableNames();

    /**
     * One table's full insight — columns, constraints, indexes, size — or
     * empty when no such user table exists (the use case turns that into the
     * typed not-found failure; a hostile or unknown name is simply absent,
     * never an error from the driver).
     */
    Optional<TableInsight> table(String name);
}

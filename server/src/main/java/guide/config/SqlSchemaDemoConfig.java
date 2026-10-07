package guide.config;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.schema.LiveDbArea;
import guide.data.schema.PgKeyStrategyTables;
import guide.data.schema.PgSchemaCatalog;
import guide.data.sql.SchemaMigrator;
import guide.domain.schema.CompareKeyStrategies;
import guide.domain.schema.ExploreSchema;
import guide.domain.schema.KeyStrategyTables;
import guide.domain.schema.SchemaCatalog;
import guide.domain.schema.SummarizeTableDdl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Topic 16 (sql-schema) wiring — the schema demos' composition root: one
 * catalog port, one key-strategy port, three use case beans, every wire
 * explicit. The SQL-side beans are @Lazy exactly like the posts substrate's:
 * contexts without a DataSource never construct them, and the full app pays
 * for them only when a demo call actually activates the area.
 *
 * The AREA REGISTRATION is this topic's deliberate variation on the posts
 * pattern: PostsDemoConfig registers the 'api' area FAKE because the router
 * has a fake to serve; the schema demos have no in-memory counterpart — they
 * are ABOUT the real database — so 'db' registers FAKE meaning "not yet
 * activated", and LiveDbArea flips it LIVE programmatically once migrations
 * have actually run. That flip is the programmatic activation seam (the
 * public scenario PATCH moves only the global mode).
 */
@Configuration
public class SqlSchemaDemoConfig {

    private final ScenarioStore scenarioStore;

    public SqlSchemaDemoConfig(ScenarioStore scenarioStore) {
        this.scenarioStore = scenarioStore;
        scenarioStore.setDataMode(SchemaCatalog.AREA, ScenarioDataMode.FAKE);
    }

    /** The once-guarded lazy activation shared by both schema adapters. */
    @Lazy
    @Bean
    LiveDbArea liveDbArea(SchemaMigrator schemaMigrator) {
        return new LiveDbArea(scenarioStore, () -> schemaMigrator);
    }

    /** The catalog adapter (information_schema + pg_catalog reader), @Lazy like every SQL-side bean. */
    @Lazy
    @Bean
    SchemaCatalog schemaCatalog(JdbcTemplate jdbcTemplate, LiveDbArea liveDbArea) {
        return new PgSchemaCatalog(jdbcTemplate, liveDbArea);
    }

    /** The key-strategy lab adapter over the V3 tables, @Lazy for the same reason. */
    @Lazy
    @Bean
    KeyStrategyTables keyStrategyTables(JdbcTemplate jdbcTemplate, LiveDbArea liveDbArea) {
        return new PgKeyStrategyTables(jdbcTemplate, liveDbArea);
    }

    @Bean
    ExploreSchema exploreSchema(SchemaCatalog schemaCatalog) {
        return new ExploreSchema(schemaCatalog);
    }

    @Bean
    SummarizeTableDdl summarizeTableDdl(SchemaCatalog schemaCatalog) {
        return new SummarizeTableDdl(schemaCatalog);
    }

    @Bean
    CompareKeyStrategies compareKeyStrategies(KeyStrategyTables keyStrategyTables) {
        return new CompareKeyStrategies(keyStrategyTables);
    }
}

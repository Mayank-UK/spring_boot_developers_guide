package guide.config;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.posts.PgPostRepository;
import guide.data.sql.SchemaMigrator;
import guide.data.sqldemo.DbDemoActivation;
import guide.data.sqldemo.PgAuditTrail;
import guide.data.sqldemo.PgInventoryRaceTrack;
import guide.data.sqldemo.PgMigrationCatalog;
import guide.data.sqldemo.PgPostSearch;
import guide.data.sqldemo.PgSqlLabQueries;
import guide.data.sqldemo.PgSqlSafetySandbox;
import guide.domain.sqldemo.AuditPostTransfer;
import guide.domain.sqldemo.AuditTrailStore;
import guide.domain.sqldemo.DbDemos;
import guide.domain.sqldemo.DbMigrationsExhibit;
import guide.domain.sqldemo.InventoryRaceTrack;
import guide.domain.sqldemo.MigrationCatalog;
import guide.domain.sqldemo.PostSearch;
import guide.domain.sqldemo.ReadAuditTrail;
import guide.domain.sqldemo.RunInventoryRace;
import guide.domain.sqldemo.RunLabQuery;
import guide.domain.sqldemo.RunSqlSafetyShowdown;
import guide.domain.sqldemo.SearchPosts;
import guide.domain.sqldemo.SqlLabQueries;
import guide.domain.sqldemo.SqlSafetySandbox;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Topic 17 (jdbc-sql) wiring — the db demo area's composition root, one
 * port→adapter and use case per bean, every wire explicit (the house rule
 * from PostsDemoConfig, restated).
 *
 * The adapters are @Lazy AND injected into their use cases through @Lazy
 * parameters — the parameter annotation is what makes Spring hand the use
 * case a resolution PROXY, so no adapter (and no DataSource touch) happens
 * at boot: the app still starts and serves with PostgreSQL unreachable,
 * and the first db-demo call pays migration + seeding once, lazily.
 *
 * The ScenarioStore's 'db' area is pinned LIVE at startup — a per-area
 * override, so even a global {"dataMode":"fake"} flip leaves these demos
 * against real PostgreSQL. An in-memory fake of "what the planner did with
 * this EXPLAIN" would be theater.
 */
@Configuration
public class JdbcSqlDemoConfig {

    public JdbcSqlDemoConfig(ScenarioStore scenarioStore) {
        // The area pin: db demos answer from live PostgreSQL, full stop.
        scenarioStore.setDataMode(DbDemos.AREA, ScenarioDataMode.LIVE);
    }

    // ---- use cases (eager; controllers inject these) ----

    @Bean
    RunSqlSafetyShowdown runSqlSafetyShowdown(@Lazy SqlSafetySandbox sandbox) {
        return new RunSqlSafetyShowdown(sandbox);
    }

    @Bean
    DbMigrationsExhibit dbMigrationsExhibit(@Lazy MigrationCatalog catalog) {
        return new DbMigrationsExhibit(catalog);
    }

    @Bean
    RunLabQuery runLabQuery(@Lazy SqlLabQueries lab) {
        return new RunLabQuery(lab);
    }

    @Bean
    SearchPosts searchPostsUseCase(@Lazy PostSearch search) {
        return new SearchPosts(search);
    }

    @Bean
    RunInventoryRace runInventoryRace(@Lazy InventoryRaceTrack track) {
        return new RunInventoryRace(track);
    }

    @Bean
    AuditPostTransfer auditPostTransfer(@Lazy AuditTrailStore store) {
        return new AuditPostTransfer(store);
    }

    @Bean
    ReadAuditTrail readAuditTrail(@Lazy AuditTrailStore store) {
        return new ReadAuditTrail(store);
    }

    // ---- adapters (lazy; nothing PostgreSQL-shaped exists until first call) ----

    @Lazy
    @Bean
    DbDemoActivation dbDemoActivation(ObjectProvider<SchemaMigrator> schemaMigrator,
            ObjectProvider<PgPostRepository> livePosts) {
        return new DbDemoActivation(schemaMigrator::getObject, livePosts::getObject);
    }

    @Lazy
    @Bean
    PgSqlSafetySandbox sqlSafetySandbox(DbDemoActivation activation,
            JdbcTemplate jdbc, DataSource dataSource) {
        return new PgSqlSafetySandbox(activation, jdbc, dataSource);
    }

    @Lazy
    @Bean
    PgMigrationCatalog migrationCatalog(DbDemoActivation activation,
            JdbcTemplate jdbc, SchemaMigrator migrator) {
        return new PgMigrationCatalog(activation, jdbc, migrator);
    }

    @Lazy
    @Bean
    PgSqlLabQueries sqlLabQueries(DbDemoActivation activation, JdbcTemplate jdbc) {
        return new PgSqlLabQueries(activation, jdbc);
    }

    @Lazy
    @Bean
    PgPostSearch postSearch(DbDemoActivation activation, JdbcTemplate jdbc) {
        return new PgPostSearch(activation, jdbc);
    }

    @Lazy
    @Bean
    PgInventoryRaceTrack inventoryRaceTrack(DbDemoActivation activation, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        return new PgInventoryRaceTrack(activation, jdbc, transactionManager);
    }

    @Lazy
    @Bean
    PgAuditTrail auditTrail(DbDemoActivation activation, JdbcTemplate jdbc, ObjectMapper mapper) {
        return new PgAuditTrail(activation, jdbc, mapper);
    }
}

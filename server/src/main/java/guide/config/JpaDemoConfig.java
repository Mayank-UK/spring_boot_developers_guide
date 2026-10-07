package guide.config;

import guide.core.scenario.ScenarioStore;
import guide.data.jpa.JpaQueryCounter;
import guide.data.jpa.PgJpaProbe;
import guide.data.jpa.PostEntityRepository;
import guide.data.sqldemo.DbDemoActivation;
import guide.domain.jpa.ReadJpaPage;
import guide.domain.jpa.RunJpaProbe;
import guide.domain.jpa.RunLockingProbe;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Topic 18 (spring-data-jpa) wiring — the 'jpa' demo area's composition root.
 * Same rules as PostsDemoConfig/JdbcSqlDemoConfig: one port→adapter and one
 * use case per bean, constructor injection everywhere, every wire explicit.
 *
 * Two deliberate differences from the 'db' area:
 * - the 'jpa' area is NOT pinned live at startup. It defaults FAKE, and the
 *   adapter's fake answer is a typed UNAVAILABLE envelope that tells you how
 *   to flip it (PATCH /api/demo/scenario {"dataMode":"live"}). The db demos
 *   are the live SQL itself; the JPA demos are MEASUREMENTS, and the mode
 *   seam is part of what they teach.
 * - activation reuses topic 17's DbDemoActivation — the same
 *   SchemaMigrator.migrate() + SeedPosts replay, paid once, lazily. No
 *   second migration path exists.
 *
 * The adapter is @Lazy and injected into its use cases through @Lazy
 * parameters, so nothing PostgreSQL-shaped (and no EntityManager use)
 * happens at boot — the degrade-gracefully rule survives JPA.
 *
 * The HibernatePropertiesCustomizer is the probe's instrument mount: it
 * hands the JpaQueryCounter to Hibernate as the EntityManagerFactory's
 * StatementInspector — one registration, every statement the factory issues
 * on a thread becomes countable by that thread.
 */
@Configuration
public class JpaDemoConfig {

    // ---- the probe's instrument (eager: the EMF build consumes it at boot) ----

    @Bean
    JpaQueryCounter jpaQueryCounter() {
        return new JpaQueryCounter();
    }

    @Bean
    HibernatePropertiesCustomizer jpaProbeInstrumentation(JpaQueryCounter counter) {
        return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, counter);
    }

    // ---- use cases (eager; the controller injects these) ----

    @Bean
    RunJpaProbe runJpaProbe(@Lazy guide.domain.jpa.JpaProbePort probePort) {
        return new RunJpaProbe(probePort);
    }

    @Bean
    RunLockingProbe runLockingProbe(@Lazy guide.domain.jpa.JpaProbePort probePort) {
        return new RunLockingProbe(probePort);
    }

    @Bean
    ReadJpaPage readJpaPage(@Lazy guide.domain.jpa.JpaProbePort probePort) {
        return new ReadJpaPage(probePort);
    }

    // ---- the adapter (lazy; nothing EntityManager-shaped exists until first call) ----
    // PgJpaProbe IS the port — one bean, one type; the use cases' JpaProbePort
    // injections resolve to it (its EntityManager arrives via @PersistenceContext,
    // the transaction-scoped shared proxy, not a constructor wire).

    @Lazy
    @Bean
    PgJpaProbe pgJpaProbe(ScenarioStore scenarioStore, ObjectProvider<DbDemoActivation> activation,
            JpaQueryCounter counter, ObjectProvider<PostEntityRepository> posts,
            PlatformTransactionManager transactionManager) {
        return new PgJpaProbe(scenarioStore, activation.getObject(), counter,
                posts.getObject(), transactionManager);
    }
}

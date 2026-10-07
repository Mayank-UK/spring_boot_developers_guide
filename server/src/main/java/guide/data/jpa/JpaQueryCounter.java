package guide.data.jpa;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * The probe's instrument: a Hibernate StatementInspector that counts every
 * SQL statement the SESSION prepares, on the thread that caused it. Registered
 * once per EntityManagerFactory through a HibernatePropertiesCustomizer
 * (JpaDemoConfig) under hibernate.session_factory.statement_inspector —
 * which makes every statement Hibernate issues on the probe's thread visible
 * to the probe and to nobody else.
 *
 * Thread-local ON PURPOSE: request threads are single-writer here, and a
 * demo that says "this page cost N queries" must not count another
 * request's statements. reset() then issued() brackets exactly the window
 * the report claims.
 */
public final class JpaQueryCounter implements StatementInspector {

    private final ThreadLocal<Integer> issued = ThreadLocal.withInitial(() -> 0);

    @Override
    public String inspect(String sql) {
        issued.set(issued.get() + 1);
        return sql;
    }

    /** Start counting from zero for THIS thread — call right before the window you claim. */
    public void reset() {
        issued.set(0);
    }

    /** Statements issued on this thread since the last reset. */
    public int issued() {
        return issued.get();
    }
}

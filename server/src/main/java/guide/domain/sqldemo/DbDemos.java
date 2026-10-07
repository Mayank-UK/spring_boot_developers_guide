package guide.domain.sqldemo;

/**
 * Shared names for the topic 17 (jdbc-sql) demo area. One area id for the
 * ScenarioStore's per-area registry — pinned LIVE at wiring time because
 * these demos ARE the live adapter: an in-memory fake of "real SQL against
 * real PostgreSQL" would be a diorama, not a demo.
 */
public final class DbDemos {

    /** The ScenarioStore area every /api/demo/db/** endpoint runs under. */
    public static final String AREA = "db";

    private DbDemos() {
    }
}

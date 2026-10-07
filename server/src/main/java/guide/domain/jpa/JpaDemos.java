package guide.domain.jpa;

/**
 * Shared names for the topic 18 (spring-data-jpa) demo area. One area id for
 * the ScenarioStore's per-area registry — deliberately NOT pinned live at
 * wiring time (unlike the 'db' area): the JPA probe area starts FAKE, and its
 * "fake" answer is a typed UNAVAILABLE envelope that says how to activate it,
 * because the probe's whole point is measuring REAL query counts against real
 * PostgreSQL — an in-memory fake of "how many queries did Hibernate issue"
 * would be a diorama with the numbers made up.
 */
public final class JpaDemos {

    /** The ScenarioStore area every /api/demo/jpa/** endpoint runs under. */
    public static final String AREA = "jpa";

    private JpaDemos() {
    }
}

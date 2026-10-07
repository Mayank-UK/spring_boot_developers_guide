package guide.domain.core.container;

/**
 * One naming convention for spotting use-case beans in a container snapshot —
 * the demo's real, working example of MULTIPLE BEANS IMPLEMENTING ONE
 * INTERFACE. The composition root registers two implementations; the use case
 * receives them as a List and honors any match, so a new convention (a
 * different project's naming style) joins by adding one bean, not by editing
 * the exhibit. This port is also intentionally Spring-free: the ambiguity it
 * demonstrates is a container concept, but the code demonstrating it obeys the
 * same dependency rule as everything else in domain/.
 */
public interface UseCaseConvention {

    /** Human label shown beside the beans this convention claimed. */
    String name();

    /** True when a bean with this class name follows the convention. */
    boolean matches(String beanClassName);
}

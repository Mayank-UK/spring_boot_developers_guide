package guide.domain.spring;

/**
 * Port (topic 04's demo): supply the raw facts of the running application.
 * The domain asks for AppInfoFacts — a record of primitives — so this
 * interface is implementable by anything: the real adapter that reads the
 * Spring context, or a test fixture with fixed values.
 */
public interface AppInfoSource {

    /** The process's self-description; never null (use cases reject that). */
    AppInfoFacts facts();
}

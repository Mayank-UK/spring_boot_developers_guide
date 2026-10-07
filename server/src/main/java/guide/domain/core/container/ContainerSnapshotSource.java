package guide.domain.core.container;

/**
 * Port (topic 05's core:container demo): supply a snapshot of the running
 * container's beans. The domain asks for plain data, so this interface is
 * implementable by anything — the real adapter that walks the Spring context,
 * or a test fixture with a fixed bean list.
 */
public interface ContainerSnapshotSource {

    /** All bean definitions of the running context; never null (use cases reject that). */
    ContainerFacts snapshot();
}

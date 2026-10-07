package guide.domain.deps;

/**
 * Port: where the summarized tree comes from. The adapter reads the
 * snapshot resource from the classpath and parses it; this interface exists
 * so the use case (and its tests) depend on the SHAPE, not the file.
 */
public interface DependencyTreeSource {

    /** The parsed snapshot; the adapter fails (throws) only if the resource is missing or malformed. */
    DependencyTreeSnapshot load();
}

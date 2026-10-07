package guide.domain.deps;

import java.util.List;

/**
 * The summarized tree the snapshot resource parses into — the wire shape of
 * GET /api/demo/deps/tree. A STATIC, honestly-labeled capture: the resource
 * file records what `mvn dependency:tree` printed when it was last run by a
 * human, and the prose beside the demo shows the regeneration command. Live
 * maven at request time is deliberately not run (minutes-slow, mutates
 * target/, and needs network) — teaching that trade-off is part of the topic.
 */
public record DependencyTreeSnapshot(
        String capturedOn, String parentBom, String importedBom, int directCount,
        List<DirectDependency> direct, List<String> conflicts) {

    /** One direct dependency of the server module, with its second-level children. */
    public record DirectDependency(String groupId, String artifactId, String version,
                                   String scope, boolean optional, List<Child> children) {

        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    /** One second-level child (transitive) under a direct dependency. */
    public record Child(String groupId, String artifactId, String version, String scope) {

        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }
}

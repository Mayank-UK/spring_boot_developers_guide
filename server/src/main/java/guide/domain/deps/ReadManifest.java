package guide.domain.deps;

import java.util.List;

/**
 * Use case: build the RUNTIME-TRUTH dependency inventory the manifest demo
 * serves — every curated artifact probed through {@link VersionProbe}, plus
 * the flagged-decision registry so the WHY ships next to the WHAT. Pure
 * orchestration over the port; no JVM access happens here (that is the
 * adapter's job), which is what makes this class trivially testable with a
 * fake probe.
 */
public final class ReadManifest {

    private final VersionProbe probe;

    public ReadManifest(VersionProbe probe) {
        this.probe = probe;
    }

    public DependencyManifest execute() {
        List<ResolvedArtifact> artifacts = CuratedArtifacts.ALL.stream()
                .map(this::resolve)
                .toList();
        return new DependencyManifest(artifacts, FlaggedDecisions.ALL);
    }

    private ResolvedArtifact resolve(CuratedArtifacts.ArtifactProbe artifact) {
        VersionProbe.ProbedClass probed = probe.probe(artifact.probeClass());
        return new ResolvedArtifact(
                artifact.groupId(), artifact.artifactId(),
                artifact.scope(), artifact.managedBy(), artifact.category(), artifact.note(),
                probed.present(), probed.version(), probed.versionSource());
    }

    /**
     * The wire shape of the manifest endpoint. {@code version} is null when
     * the class is absent (scope discipline: test jars are absent from
     * production runtimes) or the jar is manifest-silent — the demo renders
     * both honestly instead of inventing a claim.
     */
    public record DependencyManifest(List<ResolvedArtifact> artifacts,
                                     List<FlaggedDecisions.FlaggedDecision> flaggedDecisions) {
    }

    /**
     * One row: what the JVM actually loaded for this artifact. The
     * versionSource names WHERE the version came from ("manifest" =
     * Implementation-Version read from the jar's MANIFEST.MF, "jar-name" =
     * parsed from the code-source path) — because "how do you know" is a
     * fair question about any version claim.
     */
    public record ResolvedArtifact(String groupId, String artifactId, String scope,
                                   String managedBy, String category, String note,
                                   boolean present, String version, String versionSource) {
    }
}

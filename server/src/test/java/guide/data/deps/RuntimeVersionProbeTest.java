package guide.data.deps;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.deps.CuratedArtifacts;
import guide.domain.deps.ReadManifest;
import guide.domain.deps.VersionProbe;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The REAL probe on the REAL test classpath — the wire-shape drift guard:
 * every curated artifact must be PRESENT with a NON-NULL resolved version.
 * The point is not the specific numbers (the BOM bumps them constantly);
 * it is that a resolution change which silently breaks the reading method —
 * a jar stops shipping a manifest AND the jar-name fallback misses, a
 * module moves a probe class — fails HERE instead of shipping a demo that
 * renders empty versions and quietly stops teaching.
 *
 * Runs on the test classpath, where the test-scoped entries (testcontainers)
 * are also present; in a production runtime those report present=false by
 * design — scope discipline, rendered honestly by the same endpoint.
 */
class RuntimeVersionProbeTest {

    private final VersionProbe probe = new RuntimeVersionProbe();
    private final ReadManifest readManifest = new ReadManifest(probe);

    @Test
    void everyCuratedArtifactResolvesANonNullVersionOnTheTestClasspath() {
        List<ReadManifest.ResolvedArtifact> unresolved = readManifest.execute().artifacts().stream()
                .filter(artifact -> !artifact.present() || artifact.version() == null
                        || artifact.versionSource() == null)
                .toList();
        assertTrue(unresolved.isEmpty(),
                () -> "artifacts the probe could not resolve (fix the probe class or the "
                        + "fallback): " + unresolved.stream()
                        .map(artifact -> artifact.groupId() + ":" + artifact.artifactId())
                        .toList());
    }

    @Test
    void theJarNameFallbackEarnsItsKeepOnRealArtifacts() {
        List<String> jarNameResolved = readManifest.execute().artifacts().stream()
                .filter(artifact -> "jar-name".equals(artifact.versionSource()))
                .map(artifact -> artifact.artifactId() + " " + artifact.version())
                .toList();
        // measured on this repo's tree: HikariCP and flyway-core ship no
        // Implementation-Version — if this ever goes empty, an upstream started
        // shipping manifests; fine, but look twice before deleting the fallback
        assertNotNull(jarNameResolved);
        assertTrue(jarNameResolved.stream().anyMatch(entry -> entry.startsWith("HikariCP")),
                "HikariCP is the known manifest-silent jar: " + jarNameResolved);
    }

    @Test
    void absentClassesReportAbsenceInsteadOfFailing() {
        VersionProbe.ProbedClass probed = probe.probe("com.example.not.on.the.classpath.AtAll");
        assertTrue(!probed.present() && probed.version() == null);
    }
}

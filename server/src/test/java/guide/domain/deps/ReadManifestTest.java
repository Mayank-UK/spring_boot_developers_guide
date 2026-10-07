package guide.domain.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ReadManifest over a FAKE probe (the port earning its keep): present and
 * absent classes map onto the wire shape honestly, the flagged registry
 * ships beside the artifacts, and every curated artifact shows up exactly
 * once. No JVM access here — the real-probe behavior has its own test.
 */
class ReadManifestTest {

    private static final class FakeProbe implements VersionProbe {
        @Override
        public ProbedClass probe(String probeClass) {
            return switch (probeClass) {
                case "org.springframework.core.SpringVersion" ->
                        new ProbedClass(probeClass, true, "7.0.9", "manifest");
                case "net.coobird.thumbnailator.Thumbnails" ->
                        new ProbedClass(probeClass, true, "0.4.21", "manifest");
                case "com.zaxxer.hikari.HikariDataSource" ->
                        new ProbedClass(probeClass, true, "7.0.2", "jar-name");
                default -> new ProbedClass(probeClass, false, null, null);
            };
        }
    }

    private final ReadManifest readManifest = new ReadManifest(new FakeProbe());

    @Test
    void probeResultsCarryThroughToTheWireShape() {
        ReadManifest.DependencyManifest manifest = readManifest.execute();

        ReadManifest.ResolvedArtifact springCore = find(manifest, "org.springframework:spring-core");
        assertTrue(springCore.present());
        assertEquals("7.0.9", springCore.version());
        assertEquals("manifest", springCore.versionSource());

        ReadManifest.ResolvedArtifact hikari = find(manifest, "com.zaxxer:HikariCP");
        assertEquals("jar-name", hikari.versionSource());

        ReadManifest.ResolvedArtifact absent = find(manifest, "io.grpc:grpc-stub");
        assertFalse(absent.present());
        assertEquals(null, absent.version());
    }

    @Test
    void flaggedDecisionsShipBesideTheRuntimeTruth() {
        ReadManifest.DependencyManifest manifest = readManifest.execute();

        List<String> coordinates = manifest.flaggedDecisions().stream()
                .map(decision -> decision.groupId() + ":" + decision.artifactId())
                .toList();
        // the registry is append-only by design — topic 33 (observability) added
        // the logstash encoder pin; later pinned deps extend this list the same way
        assertEquals(List.of(
                "org.springdoc:springdoc-openapi-starter-webmvc-ui",
                "com.bucket4j:bucket4j_jdk17-core",
                "net.coobird:thumbnailator",
                "net.logstash.logback:logstash-logback-encoder"), coordinates);
    }

    @Test
    void everyCuratedArtifactAppearsExactlyOnce() {
        ReadManifest.DependencyManifest manifest = readManifest.execute();
        assertEquals(CuratedArtifacts.ALL.size(), manifest.artifacts().size());
        long distinct = manifest.artifacts().stream()
                .map(artifact -> artifact.groupId() + ":" + artifact.artifactId())
                .distinct().count();
        assertEquals(manifest.artifacts().size(), distinct, "coordinates must be unique");
    }

    private ReadManifest.ResolvedArtifact find(ReadManifest.DependencyManifest manifest,
                                               String coordinate) {
        return manifest.artifacts().stream()
                .filter(artifact -> (artifact.groupId() + ":" + artifact.artifactId()).equals(coordinate))
                .findFirst().orElseThrow();
    }
}

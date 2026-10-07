package guide.data.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.deps.DependencyTreeSnapshot;
import guide.domain.deps.DependencyTreeSource;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The snapshot contract: the resource exists on the classpath, parses into
 * the summarized shape, and its self-declared numbers match what the parse
 * found (a meta/tree mismatch throws — a snapshot that lies about itself is
 * worse than no snapshot). Also pins the two mediation specimens the topic
 * teaches from, so an unconscious regeneration that DROPS the conflicts
 * section fails here instead of silently degrading the lesson.
 */
class ClasspathDependencyTreeSnapshotTest {

    private final DependencyTreeSource source = new ClasspathDependencyTreeSnapshot();

    @Test
    void snapshotResourceIsPresentAndParseable() {
        DependencyTreeSnapshot snapshot = source.load();

        assertEquals("2026-10-07", snapshot.capturedOn());
        assertEquals("spring-boot-starter-parent 4.1.1", snapshot.parentBom());
        assertEquals("spring-grpc-dependencies 1.0.3", snapshot.importedBom());
        assertEquals(31, snapshot.directCount());
        assertEquals(31, snapshot.direct().size());
    }

    @Test
    void directDependenciesCarryVersionsScopesAndChildren() {
        DependencyTreeSnapshot snapshot = source.load();

        DependencyTreeSnapshot.DirectDependency web = find(snapshot,
                "org.springframework.boot:spring-boot-starter-web").orElseThrow();
        assertEquals("4.1.1", web.version());
        assertEquals("compile", web.scope());
        assertFalse(web.optional());
        assertTrue(web.children().size() >= 3, "starter-web keeps its second level");

        DependencyTreeSnapshot.DirectDependency devtools = find(snapshot,
                "org.springframework.boot:spring-boot-devtools").orElseThrow();
        assertTrue(devtools.optional(), "devtools is the pom's optional-flagged direct dep");

        DependencyTreeSnapshot.DirectDependency thumbnailator = find(snapshot,
                "net.coobird:thumbnailator").orElseThrow();
        assertEquals("0.4.21", thumbnailator.version());
        assertTrue(thumbnailator.children().isEmpty(), "thumbnailator pulls nothing transitive");
    }

    @Test
    void conflictsSectionKeepsTheMediationSpecimens() {
        DependencyTreeSnapshot snapshot = source.load();

        assertEquals(3, snapshot.conflicts().size());
        String guava = snapshot.conflicts().stream()
                .filter(line -> line.contains("guava")).findFirst().orElseThrow();
        assertTrue(guava.contains("33.4.8-android") && guava.contains("NEAREST WINS"));
        assertTrue(snapshot.conflicts().stream().anyMatch(line -> line.contains("1.16.7")),
                "the BOM-override specimen (micrometer) stays in the lesson");
    }

    private Optional<DependencyTreeSnapshot.DirectDependency> find(DependencyTreeSnapshot snapshot,
                                                                  String coordinate) {
        return snapshot.direct().stream()
                .filter(dep -> dep.coordinate().equals(coordinate))
                .findFirst();
    }
}

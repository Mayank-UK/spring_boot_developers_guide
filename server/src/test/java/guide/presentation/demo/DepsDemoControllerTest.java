package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.deps.DependencyTreeSnapshot;
import guide.domain.deps.ReadDependencyTree;
import guide.domain.deps.ReadManifest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 31's demo endpoints: the manifest wire shape the
 * client demo parses (artifacts with runtime versions + versionSource, the
 * flagged registry beside them), the tree snapshot shape (direct
 * dependencies with children, the conflicts list), and the Result → HTTP
 * mapping through the real advice — a failed tree read must leave as the
 * typed error envelope, not a raw 500.
 */
@WebMvcTest(DepsDemoController.class)
class DepsDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReadManifest readManifest;

    @MockitoBean
    private ReadDependencyTree readDependencyTree;

    @Test
    void manifestServesRuntimeVersionsAndTheFlaggedRegistry() throws Exception {
        when(readManifest.execute()).thenReturn(new ReadManifest.DependencyManifest(
                List.of(
                        new ReadManifest.ResolvedArtifact(
                                "org.springframework", "spring-core", "compile",
                                "Boot parent BOM", "web", "the framework's foundation",
                                true, "7.0.9", "manifest"),
                        new ReadManifest.ResolvedArtifact(
                                "com.zaxxer", "HikariCP", "compile",
                                "Boot parent BOM (transitive)", "data",
                                "ships no manifest version", true, "7.0.2", "jar-name"),
                        new ReadManifest.ResolvedArtifact(
                                "org.testcontainers", "testcontainers-postgresql", "test",
                                "Boot parent's testcontainers BOM", "tests",
                                "test scope: absent from a production runtime",
                                false, null, null)),
                List.of(new guide.domain.deps.FlaggedDecisions.FlaggedDecision(
                        "net.coobird", "thumbnailator", "0.4.21",
                        "single-jar, focused, no transitive weight", "26 files-media"))));

        mockMvc.perform(get("/api/demo/deps/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifacts.length()").value(3))
                .andExpect(jsonPath("$.artifacts[0].artifactId").value("spring-core"))
                .andExpect(jsonPath("$.artifacts[0].version").value("7.0.9"))
                .andExpect(jsonPath("$.artifacts[0].versionSource").value("manifest"))
                .andExpect(jsonPath("$.artifacts[1].artifactId").value("HikariCP"))
                .andExpect(jsonPath("$.artifacts[1].versionSource").value("jar-name"))
                .andExpect(jsonPath("$.artifacts[2].present").value(false))
                .andExpect(jsonPath("$.artifacts[2].version").doesNotExist())
                .andExpect(jsonPath("$.flaggedDecisions[0].artifactId").value("thumbnailator"))
                .andExpect(jsonPath("$.flaggedDecisions[0].pinnedVersion").value("0.4.21"))
                .andExpect(jsonPath("$.flaggedDecisions[0].topic").value("26 files-media"));
    }

    @Test
    void treeServesTheSnapshotSummaryWithConflicts() throws Exception {
        when(readDependencyTree.execute()).thenReturn(Result.success(new DependencyTreeSnapshot(
                "2026-10-07", "spring-boot-starter-parent 4.1.1", "spring-grpc-dependencies 1.0.3",
                2,
                List.of(
                        new DependencyTreeSnapshot.DirectDependency(
                                "org.postgresql", "postgresql", "42.7.13", "runtime", false,
                                List.of(new DependencyTreeSnapshot.Child(
                                        "org.checkerframework", "checker-qual", "3.55.1", "runtime"))),
                        new DependencyTreeSnapshot.DirectDependency(
                                "net.coobird", "thumbnailator", "0.4.21", "compile", false,
                                List.of())),
                List.of("com.google.guava:guava:33.4.8-jre omitted for conflict with 33.4.8-android — NEAREST WINS"))));

        mockMvc.perform(get("/api/demo/deps/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedOn").value("2026-10-07"))
                .andExpect(jsonPath("$.parentBom").value("spring-boot-starter-parent 4.1.1"))
                .andExpect(jsonPath("$.directCount").value(2))
                .andExpect(jsonPath("$.direct[0].artifactId").value("postgresql"))
                .andExpect(jsonPath("$.direct[0].scope").value("runtime"))
                .andExpect(jsonPath("$.direct[0].children[0].artifactId").value("checker-qual"))
                .andExpect(jsonPath("$.direct[1].optional").value(false))
                .andExpect(jsonPath("$.conflicts[0]").value(
                        "com.google.guava:guava:33.4.8-jre omitted for conflict with 33.4.8-android — NEAREST WINS"));
    }

    @Test
    void aFailedTreeReadLeavesAsTheTypedEnvelope() throws Exception {
        when(readDependencyTree.execute()).thenReturn(Result.failed(
                Failure.of(FailureKind.INTERNAL, "snapshot is missing or unparseable")));

        mockMvc.perform(get("/api/demo/deps/tree"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("snapshot is missing or unparseable"));
    }
}

package guide.domain.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * ExhibitNioFile behavior: the temp tree has a deterministic census — 3
 * files, 2 directories, the exact sorted relative paths — proving the walk
 * and relativize work; the byte total matches the written content. Cleanup
 * in finally is structural (the finally block exists), so the census itself
 * is the assertion surface.
 */
class ExhibitNioFileTest {

    @Test
    void walksTheTempTreeWithADeterministicCensus() {
        ExhibitNioFile.WalkReport report = ExhibitNioFile.walkTempTree();

        assertTrue(report.rootName().startsWith("guide-nio-exhibit"));
        assertEquals(3, report.filesVisited());
        assertEquals(2, report.directoriesVisited());
        assertEquals(java.util.List.of("chapters", "chapters/jvm", "chapters/jvm/memory.txt",
                "chapters/jvm/threads.txt", "notes.md"), report.relativePaths());
        long expectedBytes = "# temp tree\n".getBytes().length
                + "threads demo\n".getBytes().length
                + "memory model\n".getBytes().length;
        assertEquals(expectedBytes, report.totalBytes());
    }

    @Test
    void repeatedRunsAreIdempotent() {
        ExhibitNioFile.WalkReport first = ExhibitNioFile.walkTempTree();
        ExhibitNioFile.WalkReport second = ExhibitNioFile.walkTempTree();
        assertEquals(first.filesVisited(), second.filesVisited());
        assertEquals(first.relativePaths(), second.relativePaths());
    }
}

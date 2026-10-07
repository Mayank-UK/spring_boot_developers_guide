package guide.domain.teaching;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Teaching exhibit for topic 02 (jvm), java.nio.file section: the Path/Files
 * API — build a real directory tree in a temp directory, walk it, census it,
 * and ALWAYS delete what was created (deepest-first). It lives in
 * domain/teaching/ because the app's own file handling arrives with the
 * files-media topic; when real Files usage lands there, that code replaces
 * this exhibit as the quoted source.
 *
 * It touches the filesystem, but only under Files.createTempDirectory —
 * deterministic content, and the finally block leaves nothing behind.
 */
public final class ExhibitNioFile {

    /** Census of one walked tree: files, directories, byte total, sorted relative paths. */
    public record WalkReport(String rootName, int filesVisited, int directoriesVisited,
            long totalBytes, List<String> relativePaths) {

        public WalkReport {
            relativePaths = List.copyOf(relativePaths);
        }
    }

    /** Builds a tiny doc tree in a real temp directory, walks it, cleans up. */
    public static WalkReport walkTempTree() {
        try {
            Path root = Files.createTempDirectory("guide-nio-exhibit");
            try {
                Files.writeString(root.resolve("notes.md"), "# temp tree\n");
                Files.createDirectories(root.resolve("chapters/jvm"));
                Files.writeString(root.resolve("chapters/jvm/threads.txt"), "threads demo\n");
                Files.writeString(root.resolve("chapters/jvm/memory.txt"), "memory model\n");
                return walk(root);
            } finally {
                deleteRecursively(root);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("nio exhibit failed", error);
        }
    }

    private static WalkReport walk(Path root) throws IOException {
        int files = 0;
        int directories = 0;
        long totalBytes = 0;
        List<String> relativePaths = new ArrayList<>();
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path path : entries.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                relativePaths.add(root.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    directories++;
                } else {
                    files++;
                    totalBytes += Files.size(path);
                }
            }
        }
        return new WalkReport(root.getFileName().toString(), files, directories, totalBytes,
                relativePaths);
    }

    /** Files.walk is a lazy, stream-backed view: close it, then delete deepest-first. */
    private static void deleteRecursively(Path root) throws IOException {
        List<Path> deepestFirst;
        try (Stream<Path> entries = Files.walk(root)) {
            deepestFirst = entries.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : deepestFirst) {
            Files.delete(path);
        }
    }
}

package guide.content;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Snippet-source enforcement — the invariant that teaching code is quoted from
 * real repo source: the code is the source of truth, never invented.
 *
 * Two rules, checked mechanically on every run:
 * 1. RATCHET — code blocks in must-source languages that lack {@code source}
 *    are counted and must never exceed RATCHET (0). A new must-source block
 *    without a source field fails the suite.
 * 2. STRICT — a block carrying {@code source} must quote an existing repo
 *    file: the path is repo-root-relative and clean, and the code is an
 *    ordered subsequence of the file's lines after normalization
 *    (elisions and trailing teaching comments allowed — see SnippetMatcher).
 *
 * The registry comes from ContentTopics.all(), so every topic file Phase 3
 * adds is covered automatically. (verifySourcedBlock returns null on success;
 * the failures list filters those out — a latent gap that stayed invisible
 * until the first real topic landed.)
 */
class SnippetSourcesTest {

    /** 0 from the start: every must-source block carries a source. Never raise it. */
    private static final int RATCHET = 0;

    private static final ContentRegistry REGISTRY = new ContentRegistry(ContentTopics.all());

    /**
     * The repo root: tests run with server/ as the working directory, so the
     * repo root is its parent. (When the suite runs inside a container that
     * mounts ONLY server/, the parent is not the real repo — with zero topics
     * nothing is walked; from Phase 3 on, run the suite where the full repo is
     * reachable, e.g. the devcontainer or a docker run mounting the repo root.)
     */
    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    private record BlockRef(String topicId, CodeBlock block) {
    }

    private static List<BlockRef> mustSourceBlocks() {
        List<BlockRef> refs = new ArrayList<>();
        for (Topic topic : REGISTRY.getTopics()) {
            for (ContentSection section : topic.sections()) {
                for (ContentBlock block : section.blocks()) {
                    if (block instanceof CodeBlock code && SnippetMatcher.isMustSourceLanguage(code.language())) {
                        refs.add(new BlockRef(topic.id(), code));
                    }
                }
            }
        }
        return refs;
    }

    /** Full strict verification of one sourced block: syntax → exists → subsequence. */
    private static String verifySourcedBlock(BlockRef ref) {
        CodeBlock block = ref.block();
        String source = block.source();
        if (source == null) {
            return ref.topicId() + "/" + block.id() + " has no source";
        }
        String syntaxError = SnippetMatcher.sourcePathError(source);
        if (syntaxError != null) {
            return ref.topicId() + "/" + block.id() + ": " + syntaxError;
        }
        Path absolute = REPO_ROOT.resolve(source).normalize();
        if (!absolute.startsWith(REPO_ROOT)) {
            return ref.topicId() + "/" + block.id() + ": source must resolve under the repo root: " + source;
        }
        if (!Files.isRegularFile(absolute)) {
            return ref.topicId() + "/" + block.id() + ": source file not found: " + source;
        }
        String sourceText;
        try {
            sourceText = Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException error) {
            return ref.topicId() + "/" + block.id() + ": source unreadable: " + source + " (" + error + ")";
        }
        SnippetMatcher.QuoteCheck check = SnippetMatcher.isQuotedFrom(block.code(), sourceText, block.language());
        if (!check.ok()) {
            return ref.topicId() + "/" + block.id() + ": snippet drifted from " + source
                    + " — first line with no match in source: " + check.mismatchedLine();
        }
        return null;
    }

    @Test
    void everyMustSourceBlockCarriesASource() {
        List<String> unsourced = mustSourceBlocks().stream()
                .filter(ref -> ref.block().source() == null)
                .map(ref -> ref.topicId() + " | " + ref.block().id() + " | " + ref.block().language())
                .toList();
        assertTrue(unsourced.size() <= RATCHET,
                () -> "snippet-source ratchet exceeded: " + unsourced.size() + " > " + RATCHET
                        + ". Must-source code blocks need a repo-relative \"source\" field. Offenders:\n"
                        + String.join("\n", unsourced.stream().limit(15).toList()));
    }

    @Test
    void everySourcedBlockIsQuotedFromRealRepoSource() {
        List<String> failures = mustSourceBlocks().stream()
                .filter(ref -> ref.block().source() != null)
                .map(SnippetSourcesTest::verifySourcedBlock)
                .filter(failure -> failure != null)
                .toList();
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }
}

package guide.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import guide.domain.arch.ArchExemptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * THE DEPENDENCY-RULE TEST (topic 32) — the architecture enforced by the
 * build, mechanically:
 *
 *   presentation  ──▶  domain  ◀──  data        (dependencies point inward)
 *
 * …plus the supporting rules that keep the shape honest: core and content
 * import nothing outward, data never reaches presentation, the domain
 * touches no web/persistence machinery, and every deliberate bend is one
 * of the ledgered exemptions in guide.domain.arch.ArchExemptions — the
 * single source the /api/demo/arch/map endpoint also serves.
 *
 * HOW IT CHECKS: a SOURCE-LEVEL import scan of src/main/java. A bytecode
 * walk (constant pools via ASM, or ArchUnit) would catch reflective edges
 * too — at the cost of a new dependency and code a reader cannot hold in
 * their head. This app has no reflection-across-layers (the tracer's map
 * walk aside, which lives in data/ by design), so import statements ARE
 * the dependency graph; scanning them keeps the test honest, teachable,
 * and dependency-free — you can read every rule below in one sitting.
 * The day a reflective edge appears, the tracer's map is the cross-check.
 *
 * SENSITIVITY: theDetectorFiresOnInjectedViolations proves (enabled, every
 * build) that each rule reports synthetic violations; aDeliberateViolation…,
 * deliberately @Disabled, poisons the REAL scan with one injected violation
 * and would fail the suite if enabled — flip the annotation off locally,
 * watch it go red, flip it back.
 */
class DependencyRuleTest {

    // ---- the rules under test -------------------------------------------------

    /** Imports the domain may never make — the machinery the rule exists to keep out. */
    private static final List<String> DOMAIN_BANNED_PREFIXES = List.of(
            "org.springframework.web.",
            "org.springframework.jdbc.",
            "org.springframework.data.",
            "org.springframework.orm.",
            "org.springframework.boot.",
            "org.springframework.transaction.",
            "jakarta.persistence.",
            "jakarta.servlet.");

    /** What the domain may import freely: the JDK and its own shared kernel. */
    private static final List<String> DOMAIN_FREE_PREFIXES = List.of(
            "java.", "javax.", "guide.core.", "guide.domain.");

    /** The content registry is an island: itself, the JDK, and rendering annotations. */
    private static final List<String> CONTENT_FREE_PREFIXES = List.of(
            "java.", "javax.", "guide.content.", "com.fasterxml.jackson.annotation.",
            "tools.jackson.");

    private static final Pattern PACKAGE_LINE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_LINE = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;", Pattern.MULTILINE);

    /**
     * Text blocks, block comments, and line comments are stripped BEFORE
     * matching — the topics quote code inside Java string literals, and an
     * import that exists only inside a quoted snippet is TEACHING, not a
     * dependency. (Order matters: text blocks first, so a snippet
     * containing comment-looking lines cannot confuse the comment strips.)
     */
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** One scanned source file: its path under src/main/java, its package, its imports. */
    record SourceFile(String path, String packageName, List<String> imports) {
    }

    // ---- rule 1: the spine ------------------------------------------------------

    @Test
    void domainNeverImportsTheDataOrPresentationLayers() {
        assertNoViolations(violationsFor(scan()), "presentation → domain ← data");
    }

    @Test
    void dataAndCoreAndContentNeverImportOutward() {
        assertNoViolations(violationsFor(scan()), "outward imports");
    }

    @Test
    void theDomainTouchesNoWebOrPersistenceMachinery() {
        assertNoViolations(violationsFor(scan()), "banned machinery");
    }

    // ---- rule 2: the ledger -----------------------------------------------------

    @Test
    void everyOutsideImportInTheDomainIsLedgered_andNoStaleEntries() {
        List<SourceFile> files = scan();
        List<String> violations = violationsFor(files);
        assertNoViolations(violations, "ledgered exemptions");
        // and the ledger matches ArchExemptions — the SAME list /api/demo/arch/map
        // serves, so the map can never drift from what this test enforces
        assertEquals(ArchExemptions.teachingExhibits().size(),
                countExhibitFiles(files), "teaching exhibits on disk match the served ledger");
    }

    // ---- sensitivity ------------------------------------------------------------

    @Test
    void theDetectorFiresOnInjectedViolations() {
        // empty ledger on purpose: this proves the DETECTOR rules fire; the
        // ledger-staleness rules are exercised against the real tree above
        List<String> violations = violationsFor(List.of(
                new SourceFile("guide/presentation/demo/InjectedController.java",
                        "guide.presentation.demo",
                        List.of("guide.data.posts.InMemoryPostRepository")),
                new SourceFile("guide/domain/injected/InjectedUseCase.java",
                        "guide.domain.injected",
                        List.of("guide.data.posts.PostRepository", "guide.presentation.ContentController")),
                new SourceFile("guide/domain/injected/InjectedUseCase.java",
                        "guide.domain.injected",
                        List.of("org.springframework.web.bind.annotation.RestController")),
                new SourceFile("guide/domain/injected/InjectedUseCase.java",
                        "guide.domain.injected",
                        List.of("org.springframework.kafka.KafkaTemplate")),
                new SourceFile("guide/data/injected/InjectedAdapter.java",
                        "guide.data.injected",
                        List.of("guide.presentation.ContentController")),
                new SourceFile("guide/core/InjectedCore.java",
                        "guide.core",
                        List.of("guide.domain.posts.PostRepository"))),
                List.of(), List.of());

        assertEquals(8, violations.size(),
                "every injected violation is reported:\n" + String.join("\n", violations));
        assertTrue(violations.stream().anyMatch(v -> v.contains("InjectedController")
                && v.contains("guide.data")), "presentation → data fires");
        assertTrue(violations.stream().anyMatch(v -> v.contains("guide.data")
                && v.contains("InjectedUseCase")), "domain → data fires");
        assertTrue(violations.stream().anyMatch(v -> v.contains("guide.presentation")
                && v.contains("InjectedUseCase")), "domain → presentation fires");
        assertTrue(violations.stream().anyMatch(v -> v.contains("RestController")),
                "banned machinery fires");
        assertTrue(violations.stream().anyMatch(v -> v.contains("KafkaTemplate")),
                "unledgered outside import fires");
        assertTrue(violations.stream().anyMatch(v -> v.contains("InjectedAdapter")
                && v.contains("guide.presentation")), "data → presentation fires");
        assertTrue(violations.stream().anyMatch(v -> v.contains("InjectedCore")),
                "core importing outward fires");
    }

    /**
     * teaching exhibit — the SENSITIVITY PROOF. Enabled, this test is
     * DELIBERATELY RED: it injects one presentation→data violation into the
     * real scanned tree and runs the same assertion the live rules run.
     * Flip @Disabled off, watch the suite fail with the detector's message,
     * flip it back — the fastest way to trust (or demo) that green means
     * the real tree is clean, not that the detector is asleep.
     */
    @Disabled("run on demand to watch the detector fire — enabled, this test is red on purpose")
    @Test
    void aDeliberateViolationInTheRealTreeWouldFailTheSuite() {
        List<SourceFile> poisoned = new ArrayList<>(scan());
        poisoned.add(new SourceFile("guide/presentation/demo/InjectedViolation.java",
                "guide.presentation.demo",
                List.of("guide.data.posts.InMemoryPostRepository")));
        assertNoViolations(violationsFor(poisoned), "the injected violation must be reported");
    }

    // ---- the engine -------------------------------------------------------------

    /**
     * All rules, one pass, human-readable messages. Each violation names the
     * file, the offending import, and the rule — the message a reviewer
     * would write in a pull request. The ledger and exhibits are parameters
     * so the sensitivity test can prove the DETECTOR fires independently of
     * what the (real) ledger says.
     */
    static List<String> violationsFor(List<SourceFile> files,
            List<ArchExemptions.DomainExemption> ledger,
            List<ArchExemptions.TeachingExhibit> exhibits) {
        Set<String> exhibitFiles = new HashSet<>();
        for (ArchExemptions.TeachingExhibit exhibit : exhibits) {
            exhibitFiles.add(exhibit.file());
        }
        Map<String, Set<String>> actualExhibitImports = new TreeMap<>();

        List<String> violations = new ArrayList<>();
        List<SourceFile> domainFiles = files.stream()
                .filter(file -> inLayer(file.packageName(), "guide.domain"))
                .toList();

        for (SourceFile file : files) {
            String pkg = file.packageName();

            if (inLayer(pkg, "guide.presentation")) {
                for (String imported : file.imports()) {
                    if (imported.startsWith("guide.data.")) {
                        if (exhibitFiles.contains(file.path())) {
                            actualExhibitImports.computeIfAbsent(file.path(), key -> new TreeSet<>())
                                    .add(imported);
                        } else {
                            violations.add(file.path() + " imports " + imported
                                    + " — presentation must depend on domain only"
                                    + " (teaching exhibits need an ArchExemptions entry)");
                        }
                    }
                }
            }

            if (inLayer(pkg, "guide.data")) {
                for (String imported : file.imports()) {
                    if (imported.startsWith("guide.presentation.")) {
                        violations.add(file.path() + " imports " + imported
                                + " — data never reaches up into presentation");
                    }
                }
            }

            if (inLayer(pkg, "guide.core")) {
                for (String imported : file.imports()) {
                    if (imported.startsWith("guide.domain.") || imported.startsWith("guide.data.")
                            || imported.startsWith("guide.presentation.")) {
                        violations.add(file.path() + " imports " + imported
                                + " — core is the innermost kernel and imports nothing outward");
                    }
                }
            }

            if (inLayer(pkg, "guide.content")) {
                for (String imported : file.imports()) {
                    if (CONTENT_FREE_PREFIXES.stream().noneMatch(imported::startsWith)) {
                        violations.add(file.path() + " imports " + imported
                                + " — the content registry is an island");
                    }
                }
            }
        }

        for (SourceFile file : domainFiles) {
            for (String imported : file.imports()) {
                if (DOMAIN_BANNED_PREFIXES.stream().anyMatch(imported::startsWith)) {
                    violations.add(file.path() + " imports " + imported
                            + " — the domain touches no web or persistence machinery"
                            + " (no ledger entry can legalize this)");
                }
                if (DOMAIN_FREE_PREFIXES.stream().noneMatch(imported::startsWith)
                        && !isLedgered(ledger, file.path(), imported)) {
                    violations.add(file.path() + " imports " + imported
                            + " — outside import without an ArchExemptions ledger entry;"
                            + " remove it, wrap it behind a port, or ledger it WITH a reason");
                }
            }
        }

        for (ArchExemptions.DomainExemption entry : ledger) {
            if (!coveredByScan(entry, domainFiles)) {
                violations.add("stale ledger entry: " + entry.file() + " no longer imports "
                        + entry.importPrefix() + "* — delete the exemption");
            }
        }

        for (ArchExemptions.TeachingExhibit exhibit : exhibits) {
            Set<String> imports = actualExhibitImports.get(exhibit.file());
            if (imports == null) {
                violations.add("stale teaching exhibit: " + exhibit.file()
                        + " no longer imports " + exhibit.importPrefix() + "* — delete the entry");
            } else {
                for (String imported : imports) {
                    if (!imported.startsWith(exhibit.importPrefix())) {
                        violations.add(exhibit.file() + " imports " + imported
                                + " — outside the exhibit's declared prefix " + exhibit.importPrefix());
                    }
                }
            }
        }

        return violations;
    }

    /** The live overload: the real ledger and exhibits against the given files. */
    static List<String> violationsFor(List<SourceFile> files) {
        return violationsFor(files, ArchExemptions.domainLedger(), ArchExemptions.teachingExhibits());
    }

    /** True when the package is the layer root itself or sits under it. */
    private static boolean inLayer(String packageName, String layerRoot) {
        return packageName.equals(layerRoot) || packageName.startsWith(layerRoot + ".");
    }

    private static boolean isLedgered(List<ArchExemptions.DomainExemption> ledger, String path,
            String imported) {
        return ledger.stream()
                .anyMatch(entry -> entry.file().equals(path) && imported.startsWith(entry.importPrefix()));
    }

    private static boolean coveredByScan(ArchExemptions.DomainExemption entry, List<SourceFile> domainFiles) {
        return domainFiles.stream()
                .filter(file -> file.path().equals(entry.file()))
                .flatMap(file -> file.imports().stream())
                .anyMatch(imported -> imported.startsWith(entry.importPrefix()));
    }

    private static int countExhibitFiles(List<SourceFile> files) {
        Set<String> actual = new TreeSet<>();
        for (SourceFile file : files) {
            if (!inLayer(file.packageName(), "guide.presentation")) {
                continue;
            }
            for (String imported : file.imports()) {
                if (imported.startsWith("guide.data.")) {
                    actual.add(file.path());
                }
            }
        }
        return actual.size();
    }

    private static void assertNoViolations(List<String> violations, String rule) {
        if (!violations.isEmpty()) {
            fail("the dependency rule (" + rule + ") is violated "
                    + violations.size() + " time(s):\n  - " + String.join("\n  - ", violations));
        }
    }

    // ---- the scan: src/main/java, parsed with two regexes -----------------------

    static List<SourceFile> scan() {
        Path root = Path.of("src", "main", "java");
        if (!Files.isDirectory(root)) {
            fail("cannot enforce the dependency rule: " + root.toAbsolutePath()
                    + " is not a directory — the test must run from the server module root");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<SourceFile> files = new ArrayList<>();
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().startsWith("package-info"))
                    .forEach(path -> files.add(parse(root, path)));
            files.sort((left, right) -> left.path().compareTo(right.path()));
            return files;
        } catch (IOException failure) {
            return fail("the dependency-rule scan failed: " + failure.getMessage());
        }
    }

    private static SourceFile parse(Path root, Path path) {
        try {
            String source = Files.readString(path);
            String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(
                    TEXT_BLOCK.matcher(source).replaceAll("")).replaceAll("")).replaceAll("");
            Matcher packageMatcher = PACKAGE_LINE.matcher(code);
            String packageName = packageMatcher.find() ? packageMatcher.group(1) : "(default)";
            List<String> imports = new ArrayList<>();
            Matcher importMatcher = IMPORT_LINE.matcher(code);
            while (importMatcher.find()) {
                imports.add(importMatcher.group(1));
            }
            return new SourceFile(root.relativize(path).toString(), packageName, List.copyOf(imports));
        } catch (IOException failure) {
            return fail("cannot read " + path + ": " + failure.getMessage());
        }
    }
}

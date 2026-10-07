package guide.domain.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * THE DRIFT GUARD — the flagged-choices registry and the real pom.xml must
 * agree in BOTH directions, mechanically:
 *
 *   1. pom → registry: every <dependency> in server/pom.xml that declares
 *      an explicit <version> (the pinned, flagged ones — dependencyManagement
 *      and build/plugin blocks excluded) must have a FlaggedDecisions entry,
 *      or someone pinned a version and left no recorded reason.
 *   2. registry → pom: every FlaggedDecisions entry must match a real pinned
 *      dependency at the same version, or the registry is narrating a
 *      decision nobody makes anymore (dependency deleted, or re-versioned
 *      without refreshing the rationale).
 *
 * This is the topic's whole drift philosophy in one test: the pom's flag
 * comments are prose, but the registry's PRESENCE is executable. A new
 * pinned dependency fails the suite until its WHY is written down.
 */
class FlaggedDecisionsPomSyncTest {

    /** Same resolution contract as SnippetSourcesTest: run from server/, repo root is the parent. */
    private static final Path POM = Path.of("..").toAbsolutePath().normalize()
            .resolve("server/pom.xml");

    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern FIELD =
            Pattern.compile("<(groupId|artifactId|version)>(.*?)</\\1>", Pattern.DOTALL);

    @Test
    void everyPinnedPomDependencyHasARegisteredRationale() throws IOException {
        Set<String> registry = registryCoordinatesWithVersion();
        Set<String> pinned = pinnedPomCoordinatesWithVersion();

        assertTrue(pinned.containsAll(registry),
                () -> "registry entries missing from the pom's pinned dependencies: "
                        + difference(registry, pinned));
        assertTrue(registry.containsAll(pinned),
                () -> "pom pins a version with NO FlaggedDecisions entry (write the WHY): "
                        + difference(pinned, registry));
    }

    @Test
    void rationalesNameTheirTopicAndStaySubstantive() {
        for (FlaggedDecisions.FlaggedDecision decision : FlaggedDecisions.ALL) {
            // topic numbers 1..44 (the curriculum's span) — widened when topic 33
            // registered its encoder pin; two-digit prefixes stay unambiguous
            assertTrue(decision.topic().startsWith("1") || decision.topic().startsWith("2")
                            || decision.topic().startsWith("3") || decision.topic().startsWith("4"),
                    "topic reference should name the curriculum topic: " + decision.topic());
            assertTrue(decision.rationale().length() > 80,
                    "a flagged rationale must carry a real reason, not a label: "
                            + decision.groupId() + ":" + decision.artifactId());
        }
    }

    // ---- helpers ----

    private static Set<String> registryCoordinatesWithVersion() {
        return FlaggedDecisions.ALL.stream()
                .map(decision -> decision.groupId() + ":" + decision.artifactId() + ":"
                        + decision.pinnedVersion())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Coordinates of <dependency> blocks (outside dependencyManagement/build) that pin a version. */
    private static Set<String> pinnedPomCoordinatesWithVersion() throws IOException {
        String pom = Files.readString(POM, StandardCharsets.UTF_8);
        String scopesStripped = pom
                .replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "")
                .replaceAll("(?s)<build>.*?</build>", "");
        Set<String> pinned = new HashSet<>();
        Matcher blocks = DEPENDENCY_BLOCK.matcher(scopesStripped);
        while (blocks.find()) {
            String body = blocks.group(1);
            String groupId = field(body, "groupId");
            String artifactId = field(body, "artifactId");
            String version = field(body, "version");
            if (version != null) {
                pinned.add(groupId + ":" + artifactId + ":" + version);
            }
        }
        return pinned;
    }

    private static String field(String dependencyBody, String name) {
        Matcher matcher = FIELD.matcher(dependencyBody);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2).trim();
            }
        }
        return null;
    }

    private static List<String> difference(Set<String> from, Set<String> notIn) {
        return from.stream().filter(item -> !notIn.contains(item)).toList();
    }
}

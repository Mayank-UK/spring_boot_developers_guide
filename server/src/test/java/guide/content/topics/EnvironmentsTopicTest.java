package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * EnvironmentsTopic registration test: the topic is served in the platform
 * group at order 7 (after debugging, order 6 — Phase 9's final entry),
 * carries its eight sections in curriculum order (terminal companion last),
 * every section and block id is unique, the typed-config section mounts the
 * env:inspector demo INLINE, and every must-source block carries a real
 * repo source path — java quoted from the typed AppConfig record, the demo
 * wiring, the inspector use case, the scenario store and parse, and the
 * demo controller; yaml from application.yml (datasource, dev-profile
 * document, cookie toggle, jwt secret) and both compose files; json from
 * the devcontainer; ts from the client demo.
 */
class EnvironmentsTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void environmentsTopicIsRegisteredWithItsEightSections() {
        Optional<Topic> found = registry.getTopicById("environments");
        assertTrue(found.isPresent(), "environments must be in the registry");
        Topic topic = found.get();
        assertEquals("Environments", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(7, topic.order());
        assertEquals(8, topic.sections().size());
        assertEquals("one-codebase-many-environments", topic.sections().getFirst().id());
        assertEquals("twelve-factor-config", topic.sections().get(1).id());
        assertEquals("typed-config-configuration-properties", topic.sections().get(2).id());
        assertEquals("profiles", topic.sections().get(3).id());
        assertEquals("env-specific-behavior", topic.sections().get(4).id());
        assertEquals("secrets-beyond-env", topic.sections().get(5).id());
        assertEquals("feature-flags", topic.sections().get(6).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("environments").orElseThrow();
        List<String> sectionIds = topic.sections().stream().map(ContentSection::id).toList();
        assertEquals(sectionIds.size(), sectionIds.stream().distinct().count(),
                "section ids must be unique");
        List<String> blockIds = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .map(block -> block.id())
                .toList();
        assertEquals(blockIds.size(), blockIds.stream().distinct().count(),
                "block ids must be unique across the topic");
    }

    @Test
    void theInspectorDemoIsMountedInlineInTheTypedConfigSection() {
        Topic topic = registry.getTopicById("environments").orElseThrow();
        ContentSection typedConfig = topic.sections().stream()
                .filter(section -> section.id().equals("typed-config-configuration-properties"))
                .findFirst().orElseThrow();
        assertTrue(typedConfig.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "env:inspector".equals(demo.demoId())),
                "the typed-config section must mount the env:inspector demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void javaYamlJsonAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("environments").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 14, "the topic quotes at least 14 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("core/AppConfig.java")),
                "the typed config record is quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("EnvironmentsDemoConfig.java")),
                "the @EnableConfigurationProperties wiring is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("InspectEnvironment.java")),
                "the inspector's redaction machinery is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ScenarioStore.java")),
                "the flag flip is quoted in the feature-flags section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ScenarioState.java")),
                "the fail-fast env parse is quoted in the twelve-factor section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("GuideConfig.java")),
                "the boot-time env read at the composition root is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("EnvDemoController.java")),
                "the demo controller is quoted in the terminal companion");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("application.yml")),
                "application.yml is quoted (datasource, profiles, cookie, jwt)");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("docker-compose.yml")),
                "the dev compose env wiring is quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("docker-compose.prod.yml")),
                "the prod compose is quoted in the one-codebase section");
        assertTrue(sourced.stream().anyMatch(block -> "json".equals(block.language())
                        && block.source().endsWith("devcontainer.json")),
                "the devcontainer is quoted (environment as code)");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("env_inspector_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "terminal tours ride as exempt bash blocks, never fabricated sources");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("environments").orElseThrow();
        int words = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof guide.content.TextBlock)
                .map(block -> (guide.content.TextBlock) block)
                .mapToInt(textBlock -> textBlock.body().split("\\s+").length)
                .sum();
        assertTrue(words >= 3000,
                "content depth mandate: the topic's text must be at least ~3,000 words (got " + words + ")");
    }
}

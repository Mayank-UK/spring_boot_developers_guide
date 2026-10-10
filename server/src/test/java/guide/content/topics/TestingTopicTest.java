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
 * TestingTopic registration test: the topic is served in the platform
 * group at order 5 (the group's fifth topic), carries its ten sections in
 * curriculum order (terminal companion last), every section and block id
 * is unique, the slice section mounts the testing:runner demo INLINE, and
 * every must-source block carries a real repo source path — java quoted
 * from this repo's own tests (the scripted-fake use case test, the Mockito
 * adapter test, the @WebMvcTest slice, the @DataJpaTest slice, the
 * rollback workaround, the whole-context apex, the runner use case), xml
 * from the pom's test artifacts, tsx from the client demo and its spec.
 * The CI section runs no hosted workflow to quote, so its contract block
 * rides as an exempt bash one-liner pair.
 */
class TestingTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void testingTopicIsRegisteredWithItsTenSections() {
        Optional<Topic> found = registry.getTopicById("testing");
        assertTrue(found.isPresent(), "testing must be in the registry");
        Topic topic = found.get();
        assertEquals("Testing", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(5, topic.order());
        assertEquals(10, topic.sections().size());
        assertEquals("the-pyramid-honestly", topic.sections().getFirst().id());
        assertEquals("junit-five-and-mockito", topic.sections().get(1).id());
        assertEquals("the-webmvc-slice", topic.sections().get(2).id());
        assertEquals("datajpatest-and-testcontainers", topic.sections().get(3).id());
        assertEquals("the-awkward-bits", topic.sections().get(4).id());
        assertEquals("client-testing", topic.sections().get(5).id());
        assertEquals("jacoco-coverage-honestly", topic.sections().get(6).id());
        assertEquals("the-ci-contract", topic.sections().get(7).id());
        assertEquals("browser-e2e", topic.sections().get(8).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("testing").orElseThrow();
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
    void theRunnerDemoIsMountedInlineWhereTaught() {
        Topic topic = registry.getTopicById("testing").orElseThrow();
        ContentSection slice = topic.sections().stream()
                .filter(section -> section.id().equals("the-webmvc-slice"))
                .findFirst().orElseThrow();
        assertTrue(slice.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "testing:runner".equals(demo.demoId())),
                "the slice section must mount the testing:runner demo inline");
    }

    @Test
    void javaXmlYamlAndTsxBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("testing").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 10, "the topic quotes at least 10 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("PostsUseCasesTest.java")),
                "the scripted-fake unit test is quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("AppInfoAdapterTest.java")),
                "the Mockito adapter test is quoted in the JUnit section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ContentControllerTest.java")),
                "the @WebMvcTest slice is quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("PostEntityRepositoryTest.java")),
                "the @DataJpaTest slice is quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("JpaDemoIntegrationTest.java")),
                "the whole-context apex is quoted in the pyramid section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("RunTestBattery.java")),
                "the runner use case is quoted beside its demo");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())
                        && block.source().endsWith("pom.xml")),
                "the test artifacts are quoted from the pom (xml)");
        assertTrue(topic.sections().stream()
                        .filter(section -> section.id().equals("the-ci-contract"))
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof CodeBlock code && "bash".equals(code.language()))
                        .map(block -> (CodeBlock) block)
                        .anyMatch(block -> "ci:commands".equals(block.id()) && block.source() == null),
                "the CI section teaches the pipeline contract as an exempt bash block — no workflow file exists to quote");
        assertTrue(sourced.stream().anyMatch(block -> "tsx".equals(block.language())
                        && block.source().endsWith("testing_runner_demo.tsx")),
                "the client runner demo is quoted from the live demo (tsx)");
        assertTrue(sourced.stream().anyMatch(block -> "tsx".equals(block.language())
                        && block.source().endsWith("testing_runner_demo.spec.tsx")),
                "the client spec is quoted in the client-testing section");
    }

    @Test
    void bashBlocksIncludingJaCoCoAndTheE2eSketchAreExemptFromSourcing() {
        Topic topic = registry.getTopicById("testing").orElseThrow();
        List<CodeBlock> bash = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code && "bash".equals(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(bash.size() >= 4, "the terminal companion and the exempt blocks are bash");
        assertTrue(bash.stream().noneMatch(block -> block.source() != null),
                "bash blocks carry no source — JaCoCo and the E2E sketch are taught in prose + commands");
    }
}

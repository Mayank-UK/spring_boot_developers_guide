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
 * ObservabilityTopic registration test: the topic is served in the platform
 * group at order 4 (the group's fourth topic), carries its ten sections in
 * curriculum order (terminal companion last), every section and block id is
 * unique, the structured-logging section mounts the obs:viewer demo INLINE
 * and the tracing section mounts obs:trace INLINE, and every must-source
 * block carries a real repo source path — xml quoted from logback-spring
 * xml and the pom, java from the filter, the ring appender, the emitter,
 * the meters, the trace probe, the span reporter and the process net, yaml
 * from application.yml's management block, ts from both client demos.
 */
class ObservabilityTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void observabilityTopicIsRegisteredWithItsTenSections() {
        Optional<Topic> found = registry.getTopicById("observability");
        assertTrue(found.isPresent(), "observability must be in the registry");
        Topic topic = found.get();
        assertEquals("Logging and Observability", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(4, topic.order());
        assertEquals(10, topic.sections().size());
        assertEquals("the-three-questions", topic.sections().getFirst().id());
        assertEquals("structured-logging", topic.sections().get(1).id());
        assertEquals("request-logging-filter", topic.sections().get(2).id());
        assertEquals("levels-that-mean-it", topic.sections().get(3).id());
        assertEquals("actuator-liveness-vs-readiness", topic.sections().get(4).id());
        assertEquals("metrics-with-micrometer", topic.sections().get(5).id());
        assertEquals("tracing", topic.sections().get(6).id());
        assertEquals("alerting-on-what-matters", topic.sections().get(7).id());
        assertEquals("process-level-safety-nets", topic.sections().get(8).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("observability").orElseThrow();
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
    void bothDemosAreMountedInlineWhereTaught() {
        Topic topic = registry.getTopicById("observability").orElseThrow();
        ContentSection logging = topic.sections().stream()
                .filter(section -> section.id().equals("structured-logging"))
                .findFirst().orElseThrow();
        assertTrue(logging.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "obs:viewer".equals(demo.demoId())),
                "the structured-logging section must mount the obs:viewer demo inline");
        ContentSection tracing = topic.sections().stream()
                .filter(section -> section.id().equals("tracing"))
                .findFirst().orElseThrow();
        assertTrue(tracing.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "obs:trace".equals(demo.demoId())),
                "the tracing section must mount the obs:trace demo inline");
    }

    @Test
    void javaXmlYamlAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("observability").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 12, "the topic quotes at least 12 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())
                        && block.source().endsWith("logback-spring.xml")),
                "the logging configuration itself is quoted (xml)");
        assertTrue(sourced.stream().anyMatch(block -> "xml".equals(block.language())
                        && block.source().endsWith("pom.xml")),
                "the encoder dependency is quoted from the pom (xml)");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("RequestLoggingFilter.java")),
                "the completion-line filter is quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("LogRingAppender.java")),
                "the ring appender is quoted in the viewer story");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ObsMetrics.java")),
                "the meters are quoted in the metrics section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("RestClientTraceProbe.java")),
                "the trace probe is quoted in the tracing section");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("RecordingSpanReporter.java")),
                "the teaching span reporter is quoted in the tracing section");
        assertTrue(sourced.stream().anyMatch(block -> "yaml".equals(block.language())
                        && block.source().endsWith("application.yml")),
                "the management yaml block is quoted in the probes section");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("viewer_demo.tsx")),
                "the client viewer is quoted from the live demo (ts)");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("trace_demo.tsx")),
                "the client trace demo is quoted from the live demo (ts)");
    }
}

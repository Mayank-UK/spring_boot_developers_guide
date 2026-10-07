package guide.content.topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.CodeBlock;
import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.ContentTopics;
import guide.content.DemoBlock;
import guide.content.SnippetMatcher;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SpringDataJpaTopic registration test: the topic is served in the data
 * group at order 3, carries its twelve sections in curriculum order
 * (terminal companion last), every section and block id is unique, the
 * db:jpa-probe demo is mounted INLINE in the n-plus-one section that
 * teaches it, and the snippet budget is honest — every must-source block
 * carries a repo source path, with the entity/adapter/repository quoted as
 * real repo files, the boot-without-a-database yaml decision quoted from
 * application.yml, the flagged pom pair quoted as xml, and at least one
 * snippet from the client demo (ts).
 */
class SpringDataJpaTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void springDataJpaTopicIsRegisteredWithItsTwelveSections() {
        Optional<Topic> found = registry.getTopicById("spring-data-jpa");
        assertTrue(found.isPresent(), "spring-data-jpa must be in the registry");
        Topic topic = found.get();
        assertEquals("JPA, Hibernate, and Spring Data", topic.title());
        assertEquals(TopicGroupId.DATA, topic.group());
        assertEquals(3, topic.order());
        assertEquals(12, topic.sections().size());
        assertEquals("what-an-orm-actually-does", topic.sections().getFirst().id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("spring-data-jpa").orElseThrow();
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
    void theProbeDemoIsMountedInlineInTheNPlusOneSection() {
        Topic topic = registry.getTopicById("spring-data-jpa").orElseThrow();
        ContentSection nPlusOne = topic.sections().stream()
                .filter(section -> section.id().equals("n-plus-one"))
                .findFirst().orElseThrow();
        assertTrue(nPlusOne.blocks().stream()
                        .anyMatch(block -> block instanceof DemoBlock demo
                                && "db:jpa-probe".equals(demo.demoId())),
                "the n-plus-one section must mount the db:jpa-probe demo inline");
    }

    @Test
    void mustSourceBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("spring-data-jpa").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 14, "the topic quotes at least 14 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().filter(block -> "java".equals(block.language())).count() >= 11,
                "the entity, converters, repository, adapter, and tests are quoted as real repo java files");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language())).count() >= 1,
                "the boot-offline jpa block is quoted from application.yml");
        assertTrue(sourced.stream().filter(block -> "xml".equals(block.language())).count() >= 1,
                "the flagged pom artifact pair is quoted from pom.xml");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())),
                "at least one snippet is quoted from the client demo (ts)");
    }

    @Test
    void theTopicTeachesAtComprehensiveDepth() {
        Topic topic = registry.getTopicById("spring-data-jpa").orElseThrow();
        int proseWords = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof guide.content.TextBlock)
                .mapToInt(block -> ((guide.content.TextBlock) block).body().split("\\s+").length)
                .sum();
        assertTrue(proseWords >= 3000 && proseWords <= 3800,
                "comprehensive depth: prose word count in [3000, 3800], measured " + proseWords);
    }
}

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
 * ScalingTopic registration test: the topic is served in the platform
 * group at order 11 (after environments 7, deployment 8, performance 9 —
 * Phase 10's curriculum slots), carries its nine sections in curriculum
 * order (terminal companion last), every section and block id is unique,
 * the statelessness section mounts the scaling:stateless demo INLINE,
 * and every must-source block carries a real repo source path — java
 * quoted from the demo's own engine and wiring, the auth topic's JWT
 * minter and refresh store, the security baseline's session posture, the
 * realtime ticker and upload guard; yaml from this repo's real k8s
 * manifests, compose file and application.yml; nginx from the edge
 * config; ts from the client demo. The curl/k6/ab tours ride as exempt
 * bash blocks, never fabricated sources.
 */
class ScalingTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void scalingTopicIsRegisteredWithItsNineSections() {
        Optional<Topic> found = registry.getTopicById("scaling");
        assertTrue(found.isPresent(), "scaling must be in the registry");
        Topic topic = found.get();
        assertEquals("Scaling", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(11, topic.order());
        assertEquals(9, topic.sections().size());
        assertEquals("what-scaling-means", topic.sections().getFirst().id());
        assertEquals("statelessness", topic.sections().get(1).id());
        assertEquals("load-balancing", topic.sections().get(2).id());
        assertEquals("replicas", topic.sections().get(3).id());
        assertEquals("data-at-scale", topic.sections().get(4).id());
        assertEquals("cdn-edge", topic.sections().get(5).id());
        assertEquals("backpressure", topic.sections().get(6).id());
        assertEquals("capacity", topic.sections().get(7).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("scaling").orElseThrow();
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
    void theStatelessDemoIsMountedInlineInTheStatelessnessSection() {
        Topic topic = registry.getTopicById("scaling").orElseThrow();
        ContentSection statelessness = topic.sections().stream()
                .filter(section -> section.id().equals("statelessness"))
                .findFirst().orElseThrow();
        assertTrue(statelessness.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "scaling:stateless".equals(demo.demoId())),
                "the statelessness section must mount the scaling:stateless demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void javaYamlNginxAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("scaling").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 15, "the topic quotes at least 15 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/scaling/ProbeStatelessness.java")),
                "the demo's own routing arithmetic is quoted where taught");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("config/ScalingDemoConfig.java")),
                "the demo's composition-root wiring is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/auth/JwtMinter.java")),
                "the stateless-token shape is quoted from the auth topic's minter");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/auth/ExchangeRefreshToken.java")),
                "the stateful-exception pattern is quoted from the refresh grant");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("config/SecurityConfig.java")),
                "the session posture is quoted from the security baseline");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/realtime/TickerFeed.java")),
                "the ticker's bounded ring and drop policy are quoted in backpressure");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("domain/files/GuardedUploadStream.java")),
                "the upload guard's mid-stream cap is quoted in backpressure");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("deploy/k8s/hpa.yml")),
                "the HPA policy is quoted from the real manifest");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("deploy/k8s/deployment.yml")),
                "replicas, rollout, probes and resources are quoted from the real deployment");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("deploy/k8s/service.yml")),
                "the Service front door is quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("deploy/k8s/ingress.yml")),
                "the ingress edge is quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith(".docker/docker-compose.yml")),
                "the compose shared-store rails are quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .allMatch(block -> block.source().endsWith("application.yml")
                                || block.source().startsWith("deploy/k8s/")
                                || block.source().endsWith(".docker/docker-compose.yml")),
                "yaml blocks come only from application.yml, the k8s manifests, or compose");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .count() >= 9,
                "k8s deployment x2, hpa, service, ingress, compose, and application.yml x2 are quoted");
        assertTrue(sourced.stream().filter(block -> "nginx".equals(block.language()))
                        .allMatch(block -> block.source().endsWith(".docker/nginx.conf")),
                "every nginx block comes from the real edge config");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("stateless_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "curl/k6/ab tours ride as exempt bash blocks, never fabricated sources");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("scaling").orElseThrow();
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

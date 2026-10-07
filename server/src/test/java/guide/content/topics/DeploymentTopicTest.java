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
 * DeploymentTopic registration test: the topic is served in the platform
 * group at order 8 (after environments, order 7), carries its twelve
 * sections in curriculum order (terminal companion last), every section and
 * block id is unique, the compose section mounts the deploy:stack demo
 * INLINE, and every must-source block carries a real repo source path —
 * dockerfile from both prod Dockerfiles, yaml from both compose files, the
 * CI workflow, application.yml (probes, graceful shutdown, lifecycle
 * budget), nginx.conf, and the six real deploy/k8s/ manifests; java from
 * the typed compose mirror, the use case, the demo wiring's controller; ts
 * from the client demo.
 */
class DeploymentTopicTest {

    private final ContentRegistry registry = new ContentRegistry(ContentTopics.all());

    @Test
    void deploymentTopicIsRegisteredWithItsTwelveSections() {
        Optional<Topic> found = registry.getTopicById("deployment");
        assertTrue(found.isPresent(), "deployment must be in the registry");
        Topic topic = found.get();
        assertEquals("Deployment", topic.title());
        assertEquals(TopicGroupId.PLATFORM, topic.group());
        assertEquals(8, topic.order());
        assertEquals(12, topic.sections().size());
        assertEquals("ship-vs-run", topic.sections().getFirst().id());
        assertEquals("docker-multi-stage", topic.sections().get(1).id());
        assertEquals("compose-stack", topic.sections().get(2).id());
        assertEquals("reverse-proxy-tls", topic.sections().get(3).id());
        assertEquals("graceful-shutdown", topic.sections().get(4).id());
        assertEquals("ci-cd", topic.sections().get(5).id());
        assertEquals("parity-and-secrets", topic.sections().get(6).id());
        assertEquals("kubernetes", topic.sections().get(7).id());
        assertEquals("rollouts-and-scaling", topic.sections().get(8).id());
        assertEquals("infrastructure-as-code", topic.sections().get(9).id());
        assertEquals("linux-survival", topic.sections().get(10).id());
        assertEquals("terminal-companion", topic.sections().getLast().id());
    }

    @Test
    void sectionAndBlockIdsAreUniqueAcrossTheTopic() {
        Topic topic = registry.getTopicById("deployment").orElseThrow();
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
    void theStackDemoIsMountedInlineInTheComposeSection() {
        Topic topic = registry.getTopicById("deployment").orElseThrow();
        ContentSection compose = topic.sections().stream()
                .filter(section -> section.id().equals("compose-stack"))
                .findFirst().orElseThrow();
        assertTrue(compose.blocks().stream()
                        .anyMatch(block -> block instanceof guide.content.DemoBlock demo
                                && "deploy:stack".equals(demo.demoId())),
                "the compose section must mount the deploy:stack demo inline");
        assertTrue(topic.sections().stream()
                        .flatMap(section -> section.blocks().stream())
                        .filter(block -> block instanceof guide.content.DemoBlock)
                        .count() == 1,
                "exactly one demo lives in the topic, beside its section");
    }

    @Test
    void dockerfileYamlNginxJavaAndTsBlocksAreSourcedFromRealRepoFiles() {
        Topic topic = registry.getTopicById("deployment").orElseThrow();
        List<CodeBlock> sourced = topic.sections().stream()
                .flatMap(section -> section.blocks().stream())
                .filter(block -> block instanceof CodeBlock code
                        && guide.content.SnippetMatcher.isMustSourceLanguage(code.language()))
                .map(block -> (CodeBlock) block)
                .toList();
        assertTrue(sourced.size() >= 20, "the topic quotes at least 20 sourced blocks");
        assertTrue(sourced.stream().allMatch(block -> block.source() != null),
                "every must-source block carries a source path");
        assertTrue(sourced.stream().anyMatch(block -> "dockerfile".equals(block.language())
                        && block.source().endsWith("Dockerfile.server.prod")),
                "the multi-stage server image skeleton is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "dockerfile".equals(block.language())
                        && block.source().endsWith("Dockerfile.client.prod")),
                "the client multi-stage image is quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("docker-compose.yml")),
                "the dev compose server service is quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("docker-compose.prod.yml")),
                "the prod compose boot gating is quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("ci.yml")),
                "the CI workflow is quoted");
        assertTrue(sourced.stream().filter(block -> "yaml".equals(block.language()))
                        .anyMatch(block -> block.source().endsWith("application.yml")),
                "application.yml is quoted (probes, graceful shutdown, lifecycle)");
        assertTrue(sourced.stream().anyMatch(block -> "nginx".equals(block.language())),
                "the reverse proxy config is quoted");
        List<String> k8sSources = sourced.stream()
                .filter(block -> "yaml".equals(block.language()))
                .map(CodeBlock::source)
                .filter(source -> source.startsWith("deploy/k8s/"))
                .distinct()
                .toList();
        assertEquals(java.util.Set.of("deploy/k8s/configmap.yml", "deploy/k8s/deployment.yml",
                        "deploy/k8s/hpa.yml", "deploy/k8s/ingress.yml", "deploy/k8s/namespace.yml",
                        "deploy/k8s/service.yml"),
                java.util.Set.copyOf(k8sSources),
                "all six real deploy/k8s/ manifests are quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("ComposeStack.java")),
                "the typed compose mirror is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("InspectDeployment.java")),
                "the deployment-story use case is quoted");
        assertTrue(sourced.stream().anyMatch(block -> "java".equals(block.language())
                        && block.source().endsWith("DeployDemoController.java")),
                "the demo controller is quoted in the terminal companion");
        assertTrue(sourced.stream().anyMatch(block -> "ts".equals(block.language())
                        && block.source().endsWith("deploy_stack_demo.tsx")),
                "the client demo is quoted from the live demo (ts)");
        assertTrue(sourced.stream().filter(block -> "bash".equals(block.language()))
                        .allMatch(block -> block.source() == null),
                "terminal tours ride as exempt bash blocks, never fabricated sources");
    }

    @Test
    void theTopicMeetsTheContentDepthMandate() {
        Topic topic = registry.getTopicById("deployment").orElseThrow();
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

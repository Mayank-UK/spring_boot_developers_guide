package guide.data.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.arch.CodebaseMapSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The computed layer map against the REAL compiled classes (this test runs
 * on target/classes): the six layers with plausible class counts, the
 * dependency-rule's own areas present (posts, content), the port/adapter
 * inventory containing the curriculum's taught pairs — PostRepository with
 * its fake, live, and routing adapters bound in PostsDemoConfig, and this
 * topic's own ports wired in ArchDemoConfig. Counts assert RANGES, never
 * totals — sibling topics add classes to the same tree, and a map that
 * broke on growth would be a map nobody maintains.
 */
class ClasspathMapAdapterTest {

    private final CodebaseMapSource.CodebaseMap map = new ClasspathMapAdapter().read();

    private Map<String, CodebaseMapSource.LayerCount> layersByName() {
        return map.layers().stream()
                .collect(Collectors.toMap(CodebaseMapSource.LayerCount::layer, layer -> layer));
    }

    @Test
    void allSixLayersArePresentWithPlausibleClassCounts() {
        assertEquals(List.of("core", "domain", "data", "presentation", "config", "content"),
                map.layers().stream().map(CodebaseMapSource.LayerCount::layer).toList(),
                "layers render in dependency-rule order, inward first");
        Map<String, CodebaseMapSource.LayerCount> layers = layersByName();

        assertTrue(layers.get("domain").classes() >= 150 && layers.get("domain").classes() <= 400,
                "domain is the largest layer: " + layers.get("domain").classes());
        assertTrue(layers.get("presentation").classes() >= 30 && layers.get("presentation").classes() <= 120,
                "presentation stays thin: " + layers.get("presentation").classes());
        assertTrue(layers.get("config").classes() >= 20, "the composition root exists");
        assertTrue(layers.get("core").classes() >= 15, "the shared kernel exists");

        List<String> domainAreas = layers.get("domain").areas().stream()
                .map(CodebaseMapSource.AreaCount::area).toList();
        assertTrue(domainAreas.contains("posts") && domainAreas.contains("content")
                        && domainAreas.contains("arch") && domainAreas.contains("validation"),
                "the taught areas are visible: " + domainAreas);
    }

    @Test
    void thePostPortShowsItsThreeAdaptersAndItsCompositionRootBinding() {
        CodebaseMapSource.PortBinding posts = map.ports().stream()
                .filter(binding -> binding.port().equals("guide.domain.posts.PostRepository"))
                .findFirst().orElseThrow();
        assertEquals(List.of(
                "guide.data.posts.InMemoryPostRepository",
                "guide.data.posts.PgPostRepository",
                "guide.data.posts.ScenarioRoutingPostRepository"),
                posts.adapters());
        assertEquals(List.of("PostsDemoConfig.postRepository"), posts.bindings(),
                "the port is bound by exactly one composition-root @Bean");
    }

    @Test
    void thisTopicsOwnPortsAreInTheInventory() {
        List<String> ports = map.ports().stream()
                .map(CodebaseMapSource.PortBinding::port).toList();
        assertTrue(ports.contains("guide.domain.arch.InternalProbe"), "InternalProbe listed: " + ports);
        assertTrue(ports.contains("guide.domain.arch.FlowInstrumentation"));
        assertTrue(ports.contains("guide.domain.arch.CodebaseMapSource"));

        CodebaseMapSource.PortBinding probe = map.ports().stream()
                .filter(binding -> binding.port().equals("guide.domain.arch.InternalProbe"))
                .findFirst().orElseThrow();
        assertEquals(List.of("guide.data.arch.RestClientInternalProbe"), probe.adapters());
        assertEquals(List.of("ArchDemoConfig.internalProbe"), probe.bindings());
    }

    @Test
    void everyListedPortHasAtLeastOneAdapterAndDataSideImplsOnly() {
        for (CodebaseMapSource.PortBinding binding : map.ports()) {
            assertTrue(binding.port().startsWith("guide.domain."), "ports live in the domain");
            assertTrue(!binding.adapters().isEmpty(), binding.port() + " has adapters");
            assertTrue(binding.adapters().stream().allMatch(adapter -> adapter.startsWith("guide.data.")),
                    binding.port() + " adapters live in data");
        }
    }
}

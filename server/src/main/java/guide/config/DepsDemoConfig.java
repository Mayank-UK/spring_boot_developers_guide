package guide.config;

import guide.data.deps.ClasspathDependencyTreeSnapshot;
import guide.data.deps.RuntimeVersionProbe;
import guide.domain.deps.DependencyTreeSource;
import guide.domain.deps.ReadDependencyTree;
import guide.domain.deps.ReadManifest;
import guide.domain.deps.VersionProbe;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 31 (dependency-management) wiring — the area's composition root,
 * following the house rule: every port→adapter wire explicit, every use
 * case a bean the thin controller calls. This config exists so the demo
 * has REAL machinery behind it (a classloader probe, a snapshot parser)
 * rather than a hardcoded list — the manifest endpoint answers from the
 * JVM that is actually serving the request.
 */
@Configuration
public class DepsDemoConfig {

    @Bean
    VersionProbe versionProbe() {
        return new RuntimeVersionProbe();
    }

    @Bean
    DependencyTreeSource dependencyTreeSource() {
        return new ClasspathDependencyTreeSnapshot();
    }

    @Bean
    ReadManifest readManifest(VersionProbe versionProbe) {
        return new ReadManifest(versionProbe);
    }

    @Bean
    ReadDependencyTree readDependencyTree(DependencyTreeSource dependencyTreeSource) {
        return new ReadDependencyTree(dependencyTreeSource);
    }
}

package guide.domain.core.container;

import java.util.List;

/**
 * One bean of the running container, as plain data — the unit the core:container
 * demo serves both in the overview (as the sample bean) and as the detail
 * response of GET /api/demo/core/beans/{name}. Everything here is a primitive
 * or String, so the wire shape is stable and the domain stays Spring-free;
 * the adapter that fills this record is the only piece that reads the
 * container's own API.
 *
 * factoryMethod is null for classpath-scanned components (the container
 * instantiated the class directly) and carries the @Bean method's name for
 * configuration-registered beans — the demo's visible proof that "explicit
 * @Bean" and "@Component scan" are two spellings of one concept, bean
 * definitions.
 */
public record BeanSnapshot(
        String name,
        String className,
        String scope,
        String factoryMethod,
        List<String> dependsOn,
        boolean proxied) {

    public BeanSnapshot {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}

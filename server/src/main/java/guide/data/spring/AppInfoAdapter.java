package guide.data.spring;

import guide.domain.spring.AppInfoFacts;
import guide.domain.spring.AppInfoSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Adapter (topic 04's demo): the one file in the chain that touches Spring's
 * own runtime surfaces. It reads Environment, ApplicationContext, and the
 * optional BuildProperties bean, and hands the domain a record of primitives
 * — the dependency rule's data half: presentation → domain ← data. Also a
 * ApplicationListener so the startup timestamp is the moment Boot reported
 * the app READY (construction time is the fallback if a request somehow
 * arrived earlier or the event never fired, e.g. a sliced test context).
 */
public final class AppInfoAdapter implements AppInfoSource, ApplicationListener<ApplicationReadyEvent> {

    /** Catalina's presence on the classpath is what "embedded tomcat" means here. */
    private static final String TOMCAT_CLASS = "org.apache.catalina.core.StandardServer";

    private static final String NAME_PROPERTY = "spring.application.name";
    private static final String PORT_PROPERTY = "server.port";
    private static final String EXPOSURE_PROPERTY = "management.endpoints.web.exposure.include";

    private final Environment environment;
    private final ApplicationContext context;
    private final BuildProperties buildProperties;
    private final long constructedAtEpochMillis = System.currentTimeMillis();
    private volatile long readyAtEpochMillis;

    /** buildProperties may be null: without the build-info goal there is no bean. */
    public AppInfoAdapter(Environment environment, ApplicationContext context, BuildProperties buildProperties) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.context = Objects.requireNonNull(context, "context");
        this.buildProperties = buildProperties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        readyAtEpochMillis = System.currentTimeMillis();
    }

    @Override
    public AppInfoFacts facts() {
        // Null-safe reads throughout: the Environment is an outside boundary,
        // and its convenience overloads (getProperty with defaults) are
        // interface default methods a test double will not run — so the
        // adapter applies its own defaults instead of relying on Spring's.
        String[] profiles = environment.getActiveProfiles();
        Integer configuredPort = environment.getProperty(PORT_PROPERTY, Integer.class);
        return new AppInfoFacts(
                environment.getProperty(NAME_PROPERTY, "unknown"),
                List.of(profiles == null ? new String[0] : profiles),
                buildProperties != null ? buildProperties.getArtifact() : null,
                buildProperties != null ? buildProperties.getVersion() : null,
                buildProperties != null && buildProperties.getTime() != null
                        ? buildProperties.getTime().toString()
                        : null,
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                readyAtEpochMillis > 0 ? readyAtEpochMillis : constructedAtEpochMillis,
                context.getBeanDefinitionNames().length,
                context.getBeanNamesForAnnotation(Configuration.class).length,
                guideBeanTypeNames(),
                ClassUtils.isPresent(TOMCAT_CLASS, getClass().getClassLoader()) ? "tomcat" : "unknown",
                configuredPort != null ? configuredPort : 8080,
                actuatorEndpoints());
    }

    /**
     * Every bean definition whose type resolves under guide/ — the raw list
     * the domain folds into its package layout. A definition whose type will
     * not resolve is skipped, never fatal: an introspection demo must not
     * 500 because one lazy bean refused to classify.
     */
    private List<String> guideBeanTypeNames() {
        List<String> typeNames = new ArrayList<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            try {
                Class<?> type = context.getType(beanName);
                if (type != null && type.getName().startsWith("guide.")) {
                    typeNames.add(type.getName());
                }
            } catch (RuntimeException skipped) {
                // unresolvable bean definition — report what resolved
            }
        }
        return List.copyOf(typeNames);
    }

    /**
     * The actuator web exposure list straight from the Environment (exactly
     * what application.yml set); Boot's default web exposure is health only.
     */
    private List<String> actuatorEndpoints() {
        String include = environment.getProperty(EXPOSURE_PROPERTY);
        if (include == null || include.isBlank()) {
            return List.of("health");
        }
        List<String> endpoints = new ArrayList<>();
        for (String part : include.split(",")) {
            if (!part.isBlank()) {
                endpoints.add(part.trim());
            }
        }
        return List.copyOf(endpoints);
    }
}

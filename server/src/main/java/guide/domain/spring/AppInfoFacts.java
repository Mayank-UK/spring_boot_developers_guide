package guide.domain.spring;

import java.util.List;

/**
 * Raw inputs of the spring-boot-fundamentals app-info demo: everything the
 * running Boot process knows about itself, reduced to primitives and Strings
 * BEFORE it crosses into the domain — the Spring-free half of the port
 * contract. The adapter that fills this record is the only piece of the demo
 * that touches a Spring type.
 */
public record AppInfoFacts(
        String applicationName,
        List<String> activeProfiles,
        String buildArtifact,
        String buildVersion,
        String buildTime,
        String javaVersion,
        String javaVendor,
        long startupEpochMillis,
        int beanDefinitionCount,
        int configurationClassCount,
        List<String> guideBeanTypeNames,
        String embeddedServer,
        int serverPort,
        List<String> actuatorEndpoints) {

    public AppInfoFacts {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        guideBeanTypeNames = guideBeanTypeNames == null ? List.of() : List.copyOf(guideBeanTypeNames);
        actuatorEndpoints = actuatorEndpoints == null ? List.of() : List.copyOf(actuatorEndpoints);
    }
}

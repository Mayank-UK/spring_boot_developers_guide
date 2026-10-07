package guide.domain.spring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The live exhibit behind topic 04's spring:app-info demo — the running
 * server's boot story as a serializable snapshot. Pure domain: no Spring
 * types, no I/O. All derivation happens here (package layout, guide-bean
 * count, uptime, "unknown" defaults) from the primitives in AppInfoFacts,
 * which keeps the numbers honest across runs: nothing in the wire shape is a
 * stable constant, so consumers assert ranges and presence, never exact
 * counts.
 */
public record AppInfoExhibit(
        String applicationName,
        List<String> activeProfiles,
        BuildView build,
        String javaVersion,
        String javaVendor,
        String startedAt,
        long uptimeSeconds,
        String embeddedServer,
        int serverPort,
        List<String> actuatorEndpoints,
        int beanDefinitionCount,
        int configurationClasses,
        int guideBeans,
        List<PackageView> packages) {

    public AppInfoExhibit {
        activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        actuatorEndpoints = actuatorEndpoints == null ? List.of() : List.copyOf(actuatorEndpoints);
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    /** Build metadata — "unknown" per field when the jar carries no build-info. */
    public record BuildView(String artifact, String version, String builtAt) {
    }

    /** One top-level package under guide/ and how many context beans live there. */
    public record PackageView(String name, int beans) {
    }

    /**
     * Assembles the exhibit from raw facts plus "now" (injected so tests pin
     * time). Startup defaults to "unknown" text and uptime is clamped at zero
     * so a clock skew can never report a negative process age.
     */
    public static AppInfoExhibit of(AppInfoFacts facts, long nowEpochMillis) {
        Objects.requireNonNull(facts, "facts");
        List<PackageView> packages = packageLayout(facts.guideBeanTypeNames());
        int guideBeans = packages.stream().mapToInt(PackageView::beans).sum();
        return new AppInfoExhibit(
                orUnknown(facts.applicationName()),
                facts.activeProfiles(),
                new BuildView(orUnknown(facts.buildArtifact()), orUnknown(facts.buildVersion()),
                        orUnknown(facts.buildTime())),
                orUnknown(facts.javaVersion()),
                orUnknown(facts.javaVendor()),
                facts.startupEpochMillis() > 0
                        ? Instant.ofEpochMilli(facts.startupEpochMillis()).toString()
                        : "unknown",
                facts.startupEpochMillis() > 0
                        ? Math.max(0, (nowEpochMillis - facts.startupEpochMillis()) / 1000)
                        : 0,
                orUnknown(facts.embeddedServer()),
                facts.serverPort(),
                facts.actuatorEndpoints(),
                facts.beanDefinitionCount(),
                facts.configurationClassCount(),
                guideBeans,
                packages);
    }

    /**
     * Groups this app's own bean type names by their top-level package under
     * guide/ (a bean in the root package lands in "(root)"), alphabetically.
     * Null entries and non-guide names are ignored — the caller may hand over
     * whatever the context resolved.
     */
    private static List<PackageView> packageLayout(List<String> typeNames) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String typeName : typeNames) {
            if (typeName == null) {
                continue;
            }
            String[] parts = typeName.split("\\.");
            if (parts.length < 2 || !"guide".equals(parts[0])) {
                continue;
            }
            String segment = parts.length == 2 ? "(root)" : parts[1];
            counts.merge(segment, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new PackageView(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}

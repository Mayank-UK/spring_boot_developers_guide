package guide.data.deps;

import guide.domain.deps.VersionProbe;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter: ask the JVM what version a jar REALLY is, in two tiers.
 *
 * Tier 1 — the jar's manifest: Package.getImplementationVersion() on the
 * class's package reads Implementation-Version out of META-INF/MANIFEST.MF.
 * This is the metadata Maven-dependency plugins and the pom agree to write;
 * for most of this repo's tree (spring-*, jackson, grpc, hibernate,
 * testcontainers, postgresql...) it simply works.
 *
 * Tier 2 — the jar's FILE NAME: some jars this app loads every day ship NO
 * Implementation-Version at all (measured in this repo: HikariCP 7.0.2 and
 * flyway-core 12.4.0, plus two testcontainers modules). For those, the
 * class's protection-domain code source points at the jar FILE, whose name
 * Maven laid out as artifactId-version.jar — parse the version out of it.
 * That path works in tests (plain ~/.m2 jars) AND in the deployed fat jar
 * (the Boot loader keeps BOOT-INF/lib/artifactId-version.jar naming).
 *
 * What this adapter deliberately does NOT do: read the pom, call maven, or
 * trust any claim that is not sitting on the classpath right now.
 */
public final class RuntimeVersionProbe implements VersionProbe {

    /** artifactId-version.jar — the repository layout Maven itself guarantees. */
    private static final Pattern JAR_NAME_VERSION = Pattern.compile("([A-Za-z0-9_.]+)-([0-9][^/]*?)\\.jar");

    @Override
    public ProbedClass probe(String probeClass) {
        Class<?> loaded;
        try {
            // initialize=false: reading metadata must not run static blocks
            loaded = Class.forName(probeClass, false, RuntimeVersionProbe.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return new ProbedClass(probeClass, false, null, null);
        } catch (LinkageError e) {
            return new ProbedClass(probeClass, false, null, null);
        }
        String manifestVersion = loaded.getPackage().getImplementationVersion();
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return new ProbedClass(probeClass, true, manifestVersion, "manifest");
        }
        return fromJarName(loaded, probeClass);
    }

    private static ProbedClass fromJarName(Class<?> loaded, String probeClass) {
        try {
            String location = loaded.getProtectionDomain().getCodeSource().getLocation().toString();
            Matcher matcher = JAR_NAME_VERSION.matcher(location);
            if (matcher.find()) {
                return new ProbedClass(probeClass, true, matcher.group(2), "jar-name");
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no code source (synthetic/test environment) — fall through to honest null
        }
        return new ProbedClass(probeClass, true, null, null);
    }
}

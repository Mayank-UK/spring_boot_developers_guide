package guide.domain.deps;

/**
 * Port: how the deps area learns a jar's RUNTIME version. The domain owns
 * WHAT it wants to know (a class is on the classpath; what version its
 * jar claims); the adapter owns HOW the JVM is asked. Tests swap in a fake
 * probe with fixed versions, which is exactly how this topic's demo can be
 * reasoned about without launching anything.
 *
 * The honest rule this port encodes: the pom CLAIMS a version, the
 * classpath CARRIES one, and the two can disagree (mediation, BOM
 * overrides, fat-jar repackaging). Only the loaded class is ground truth.
 */
public interface VersionProbe {

    /**
     * Probe one fully-qualified class name. Never throws for a missing
     * class — absence is DATA here (a test-scoped jar is legitimately
     * absent from a production runtime), so it reports
     * {@code present=false} instead of failing.
     */
    ProbedClass probe(String probeClass);

    /**
     * What the JVM told us about one probed class. {@code version} is null
     * when the jar carries no version metadata at all (real cases in this
     * repo's tree: HikariCP and flyway-core ship no Implementation-Version
     * in their manifests — the adapter's jar-name fallback covers them).
     */
    record ProbedClass(String probeClass, boolean present, String version, String versionSource) {
    }
}

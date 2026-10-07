package guide.domain.deps;

import java.util.List;

/**
 * The flagged-choices registry: WHY every dependency that carries an
 * EXPLICIT version in server/pom.xml was pinned. It mirrors the pom's
 * flag comments as structured data so the manifest demo can serve the
 * decision log next to the runtime versions.
 *
 * THE DRIFT GUARD (this is the point of the class): FlaggedDecisionsPomSyncTest
 * parses the real pom.xml and fails in BOTH directions —
 *  - a pom dependency with an explicit <version> that is NOT registered
 *    here ("someone pinned a version and left no reason"), and
 *  - a registry entry whose coordinate no longer exists in the pom
 *    ("stale rationale lying about a decision nobody makes anymore").
 * Keeping the registry honest is thus an executable contract, not a
 * convention. Everything versionless in the pom (the BOM-managed majority)
 * deliberately has NO entry here — those choices delegate their version,
 * and the pom's inline comments tell that story in place.
 */
public final class FlaggedDecisions {

    private FlaggedDecisions() {
    }

    /**
     * One flagged pin: the coordinate, the pinned version, the compressed
     * rationale (the pom comment's essence), and the topic that needed it.
     */
    public record FlaggedDecision(String groupId, String artifactId, String pinnedVersion,
                                  String rationale, String topic) {
    }

    public static final List<FlaggedDecision> ALL = List.of(
            new FlaggedDecision("org.springdoc", "springdoc-openapi-starter-webmvc-ui", "3.1.1",
                    "The 3.x line targets Spring Boot 4 / Framework 7 (its own parent "
                            + "POM is starter-parent 4.1.0 — this repo's exact Boot minor); "
                            + "the 2.x line ends at Boot 3.x and must not be used. Managed "
                            + "nowhere: neither Boot's BOM nor any imported BOM carries "
                            + "springdoc, so the version must live in this pom — pinned to "
                            + "the 3.x line's latest stable, verified on Central.",
                    "12 api-documentation"),
            new FlaggedDecision("com.bucket4j", "bucket4j_jdk17-core", "8.21.0",
                    "No first-party Boot integration exists — 'spring-boot-starter-"
                            + "bucket4j' returns zero results on Central, and the only "
                            + "ecosystem starter (com.giffing.bucket4j, 0.12.10) is "
                            + "third-party, Boot 3.x-only, and property-driven in a way "
                            + "that hides the machinery. Chosen instead: the framework-"
                            + "agnostic core jar for JDK 17+ — no Spring coupling, "
                            + "Boot-4 compatible by construction, wired as an explicit "
                            + "bean like every other adapter in this app.",
                    "25 security"),
            new FlaggedDecision("net.coobird", "thumbnailator", "0.4.21",
                    "The single-jar image-resizing library, chosen over imgscalr "
                            + "(dormant since 2019) and hand-rolled java.awt scaling: "
                            + "fluent API over BufferedImage, EXIF orientation, aspect-"
                            + "preserving bounding box, no native dependencies, no "
                            + "framework coupling. No Boot starter exists and none is "
                            + "wanted — it is a plain library call inside the "
                            + "CreateThumbnail use case.",
                    "26 files-media"),
            new FlaggedDecision("net.logstash.logback", "logstash-logback-encoder", "9.0",
                    "The LogstashEncoder behind the JSON logging personality in "
                            + "logback-spring.xml. NOT managed by the Boot BOM, so the "
                            + "version is explicit: 9.0 is the line's latest stable "
                            + "(Central maven-metadata), and compatibility was verified "
                            + "against the published poms and jar — it builds on Logback "
                            + "1.5.20 provided-scope and targets Java 17 bytecode, "
                            + "comfortably inside this repo's Boot 4.1.1 BOM (Logback "
                            + "1.5.38) on Java 25.",
                    "33 observability"),
            new FlaggedDecision("io.github.resilience4j", "resilience4j-spring-boot3", "2.3.0",
                    "The curriculum's named circuit-breaker library. The artifact name "
                            + "says spring-boot3 but 2.3.0 is the Boot-4-compatible line: "
                            + "dependency:tree under this repo's Boot 4.1.1 parent drags NO "
                            + "Boot-3 Spring jars (only the versionless r4j core/annotations "
                            + "the BOM resolves), and the context boots clean beside actuator "
                            + "and micrometer-prometheus. Used PROGRAMMATICALLY ONLY — the "
                            + "CircuitBreaker lives behind the domain-owned BreakerPort with "
                            + "the r4j types confined to the data/reliability adapter, so the "
                            + "starter's autoconfig/property surface is never engaged and "
                            + "nothing hides the machinery the topic teaches.",
                    "39 reliability"));
}

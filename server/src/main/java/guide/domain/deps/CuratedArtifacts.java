package guide.domain.deps;

import java.util.List;

/**
 * The curated key-artifact list the manifest demo reports on: for each
 * artifact, ONE representative class whose package metadata answers "what
 * version is ACTUALLY loaded right now". Every probe class here was chosen
 * against the real resolved tree (mvn dependency:tree, Boot 4.1.1 parent)
 * and empirically verified to load — including the two Jackson generations
 * that coexist in this repo (2.21.5 under springdoc, 3.1.5 everywhere
 * else), which is the single best argument for runtime truth over pom
 * claims this codebase can make.
 *
 * managedBy mirrors the pom's own story: most artifacts declare NO version
 * (the Boot parent BOM or the spring-grpc BOM owns it); the three flagged
 * pins carry their version in the pom with a comment, and those three also
 * appear in {@link FlaggedDecisions} — the pom-sync test fails if either
 * side drifts.
 *
 * scope is the DECLARED scope from the pom ("compile" for transitives that
 * the tree shows as such), kept for display; the manifest endpoint marks
 * test-scoped entries present=false when they are probed in a runtime that
 * does not carry them — scope discipline made observable.
 */
public final class CuratedArtifacts {

    private CuratedArtifacts() {
    }

    /**
     * One row of the manifest: the coordinate, the probe class, and the
     * teaching note explaining what this artifact IS and where its version
     * comes from.
     */
    public record ArtifactProbe(String groupId, String artifactId, String probeClass,
                                String scope, String managedBy, String category, String note) {
    }

    /** Verified against the resolved tree of this repo (2026-10-07). */
    public static final List<ArtifactProbe> ALL = List.of(
            new ArtifactProbe("org.springframework", "spring-core",
                    "org.springframework.core.SpringVersion", "compile",
                    "Boot parent BOM", "web",
                    "the framework's own foundation; SpringVersion is the canonical probe class"),
            new ArtifactProbe("org.springframework", "spring-web",
                    "org.springframework.http.MediaType", "compile",
                    "Boot parent BOM", "web",
                    "REST plumbing: converters, headers, client/server HTTP abstractions"),
            new ArtifactProbe("org.springframework", "spring-webmvc",
                    "org.springframework.web.servlet.DispatcherServlet", "compile",
                    "Boot parent BOM", "web",
                    "the front controller every @RestController request rides through"),
            new ArtifactProbe("org.apache.tomcat.embed", "tomcat-embed-core",
                    "org.apache.catalina.core.StandardServer", "compile",
                    "Boot parent BOM (transitive)", "web",
                    "the actual HTTP server under starter-web — transitive, BOM-aligned"),
            new ArtifactProbe("tools.jackson.core", "jackson-databind",
                    "tools.jackson.databind.ObjectMapper", "compile",
                    "Boot parent BOM", "serialization",
                    "Jackson 3 (tools.jackson.* packages) — Boot 4's JSON engine"),
            new ArtifactProbe("com.fasterxml.jackson.core", "jackson-core",
                    "com.fasterxml.jackson.core.JsonFactory", "compile",
                    "springdoc's line (Boot parent BOM versions it)", "serialization",
                    "Jackson 2, dragged in ONLY by springdoc/swagger — two Jackson "
                            + "generations coexist by package split; coordinates are namespaces"),
            new ArtifactProbe("org.hibernate.orm", "hibernate-core",
                    "org.hibernate.Session", "compile",
                    "Boot parent BOM (transitive)", "data",
                    "the JPA provider under starter-data-jpa"),
            new ArtifactProbe("com.zaxxer", "HikariCP",
                    "com.zaxxer.hikari.HikariDataSource", "compile",
                    "Boot parent BOM (transitive)", "data",
                    "the connection pool — ships NO manifest version, so the demo "
                            + "reads the jar name: the fallback earning its keep"),
            new ArtifactProbe("org.postgresql", "postgresql",
                    "org.postgresql.Driver", "runtime",
                    "Boot parent BOM", "data",
                    "runtime scope ON PURPOSE: compile code speaks java.sql, never "
                            + "org.postgresql — the scope enforces the rule"),
            new ArtifactProbe("org.flywaydb", "flyway-core",
                    "org.flywaydb.core.Flyway", "compile",
                    "Boot parent BOM", "data",
                    "schema migrations — also manifest-silent, jar-name fallback"),
            new ArtifactProbe("io.micrometer", "micrometer-core",
                    "io.micrometer.core.instrument.MeterRegistry", "compile",
                    "Boot parent BOM (transitive)", "observability",
                    "the metrics facade actuator wires; the BOM overrode a 1.16.7 "
                            + "transitive request to 1.17.1 — see the tree's conflicts"),
            new ArtifactProbe("org.springframework.security", "spring-security-config",
                    "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",
                    "compile", "Boot parent BOM", "security",
                    "the filter-chain machinery topics 23-24 configure"),
            new ArtifactProbe("org.aspectj", "aspectjweaver",
                    "org.aspectj.weaver.World", "compile",
                    "Boot parent BOM (transitive)", "aop",
                    "the weaver behind @Transactional/@Async/@Cacheable proxies"),
            new ArtifactProbe("io.grpc", "grpc-stub",
                    "io.grpc.stub.AbstractStub", "compile",
                    "spring-grpc BOM", "rpc",
                    "generated stub base classes — versionless in the pom ON PURPOSE: "
                            + "the imported spring-grpc BOM aligns them with the codegen"),
            new ArtifactProbe("io.grpc", "grpc-protobuf",
                    "io.grpc.protobuf.ProtoUtils", "compile",
                    "spring-grpc BOM", "rpc",
                    "protobuf wire integration for gRPC — the branch whose guava "
                            + "33.4.8-android won mediation (see the tree's conflicts)"),
            new ArtifactProbe("org.springdoc", "springdoc-openapi-starter-webmvc-ui",
                    "org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc", "compile",
                    "PINNED in pom (flagged)", "docs",
                    "explicitly pinned — the 3.x line targets Boot 4; the flagged "
                            + "registry carries the version and the full why"),
            new ArtifactProbe("com.bucket4j", "bucket4j_jdk17-core",
                    "io.github.bucket4j.Bucket", "compile",
                    "PINNED in pom (flagged)", "security",
                    "explicitly pinned — no Boot starter exists; the core jar is wired "
                            + "as a plain bean"),
            new ArtifactProbe("net.coobird", "thumbnailator",
                    "net.coobird.thumbnailator.Thumbnails", "compile",
                    "PINNED in pom (flagged)", "files",
                    "explicitly pinned — single-jar image resizing, chosen over dormant "
                            + "alternatives"),
            new ArtifactProbe("org.testcontainers", "testcontainers",
                    "org.testcontainers.containers.GenericContainer", "test",
                    "Boot parent's testcontainers BOM (transitive)", "tests",
                    "the engine under the postgres/mongo modules — test scope, so "
                            + "absent from a production runtime by design"),
            new ArtifactProbe("org.testcontainers", "testcontainers-postgresql",
                    "org.testcontainers.containers.PostgreSQLContainer", "test",
                    "Boot parent's testcontainers BOM", "tests",
                    "real PostgreSQL in tests — the 2.x RENAMED artifact (1.x "
                            + "'postgresql' stops at 1.21.4)"),
            new ArtifactProbe("org.testcontainers", "testcontainers-mongodb",
                    "org.testcontainers.mongodb.MongoDBContainer", "test",
                    "Boot parent's testcontainers BOM", "tests",
                    "the 2.x renamed Mongo module — manifest-silent, jar-name fallback"),
            new ArtifactProbe("org.testcontainers", "testcontainers-junit-jupiter",
                    "org.testcontainers.junit.jupiter.Testcontainers", "test",
                    "Boot parent's testcontainers BOM", "tests",
                    "@Testcontainers/@Container lifecycle — also the 2.x rename of "
                            + "'junit-jupiter'"));
}

package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 31 — Dependency management. Group platform, order 2. Maven taught
 * from THIS repo's real pom — the richest specimen in the guide: a parent
 * BOM, an imported BOM, the enforcer's requireUpperBoundDeps rule with its
 * recorded exclusions and upper-bound pin, five flagged pins with recorded
 * rationales, and runtime-version truth read from the classes the JVM
 * actually loaded. The demo (deps:manifest) serves that runtime truth plus
 * the summarized dependency:tree snapshot; the flagged registry is guarded
 * against pom drift by an executable sync test.
 */
public final class DependencyManagementTopic {

    private DependencyManagementTopic() {
    }

    public static Topic topic() {
        return new Topic("dependency-management", "Dependency Management",
                "Every dependency is product surface — attack surface, maintenance debt, "
                        + "and supply-chain risk at once. How this repo's Maven pom turns forty "
                        + "direct dependencies into one coherent, upgradable whole: parent BOM vs "
                        + "imported BOM, scopes as architecture, dependency:tree and version "
                        + "mediation, the enforcer as CI policy, the runtime-version manifest as "
                        + "a drift guard, Docker layer caching economics, and the decision log "
                        + "behind every non-managed pin.",
                TopicGroupId.PLATFORM, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("dependencies-are-product-surface", "Dependencies are product surface",
                        "What your app ships is mostly other people's code — and every line of it is yours to answer for.",
                        text("surface:stake", """
                                A Spring Boot application is mostly OTHER PEOPLE'S CODE. This repo declares 40 direct dependencies in its pom; after Maven resolves the transitive closure, the runtime classpath carries well over two hundred jars — the framework, the server, the JSON engine, the connection pool, the drivers, the testing machinery. Your own code is the thin layer on top. That inversion is the modern bargain: you get decades of engineering for free, and in exchange EVERY JAR IS PRODUCT SURFACE — it ships inside your artifact, runs with your privileges, parses your users' input, and breaks your production when it breaks.

                                Three costs ride every coordinate you add. ATTACK SURFACE: the Log4Shell lesson was not "logging is bad", it was "one jar nobody thought about parsed JNDI lookups in log messages" — CVE risk scales with the amount of third-party code that handles untrusted input, and every dependency's transitive children count too. MAINTENANCE DEBT: each version you froze ages; each upgrade you defer compounds; a dependency nobody upgrades for two years turns a routine bump into a migration project. SUPPLY-CHAIN RISK: you install what you build, so a compromised artifact (a hijacked maintainer account, a typosquatted name, a poisoned build) becomes code execution in YOUR deployment — which is why checksums, reproducible builds, and a short, deliberate dependency list are security controls, not hygiene.

                                The 'left-pad' empathy paragraph belongs here because it generalizes far beyond JavaScript. In 2016 an eleven-line npm package was unpublished by its author; in an hour, build pipelines worldwide broke — not because the code was valuable, but because thousands of dependency graphs contained it at depth 4 where nobody had looked. The Java ecosystem is calmer (Central artifacts are immutable, and one maintainer cannot vanish a released jar), but the STRUCTURAL lesson is identical: your dependency graph is a system you operate, most of whose nodes you have never read, chosen partly by people you have never met, several levels below the decisions you remember making. AopTopic quoted the proxy machinery; this topic quotes the pom that imports it — same rule: the code is the source of truth, and the pom is code."""),
                        text("surface:our-count", """
                                THIS REPO is the honest specimen because it was assembled the way real projects are: a dependency per topic, each one a decision. Forty direct coordinates — seventeen Boot starters (web, validation, actuator, aspectj, test, graphql, jdbc, data-jpa, data-jpa-test, data-redis, data-mongodb, security, oauth2-resource-server, websocket, restclient, webclient, mail), the Flyway pair, the PostgreSQL driver, the spring-grpc starter pair plus two grpc-java artifacts, the five-artifact observability set (the logstash encoder, the micrometer tracing bridge, Boot's brave tracing module, the prometheus registry, brave's slf4j decorator), springdoc, Bucket4j, Thumbnailator, Resilience4j, devtools, and the test-side set (spring-security-test, Boot's webmvc-test and testcontainers modules, and three Testcontainers modules). Every one of them carries a comment saying WHICH TOPIC needed it and WHY it was chosen — not as ceremony, but because six months later "why is this here" is the first question every removal or upgrade asks. The count itself is a taught number: 40 direct is what COMPREHENSIVE coverage costs; a focused service should carry a fraction of it, and the rubric for saying no comes at the end of this topic.""")),
                section("versions-scopes-and-the-bom", "Versions, scopes, and the BOM",
                        "SemVer's real limits, the two ways a Maven build centralizes versions — parent BOM and imported BOM — and scopes as architecture, not bookkeeping.",
                        text("ver:semver", """
                                VERSION NUMBERS look like SemVer (MAJOR.MINOR.PATCH: incompatible / compatible-new-stuff / fixes) and SemVer is a genuinely useful contract — for the library AUTHOR. From the consumer side, trust it with a grain of salt: "compatible" means the author believes no intended behavior changed, but every library of size has accidental API surface someone depends on (Spring's own `spring-core` quietly moves internals between minors), and MAJOR releases arrive with behavioral changes that are technically documented and operationally brutal (Hibernate 6 to 7 changed defaults this repo had to read about the hard way). The practical consumer rules: PATCHES are nearly free, MINORS are usually safe and always worth a changelog skim, MAJORS are migrations — budget them like a feature, not a chore. And treat anything that is not a stable release as radioactive: SNAPSHOT (someone's moving working copy — unreproducible builds), alpha/beta/RC/milestone (published to different repositories, explicitly unsupported, fine for evaluation, never for production). This repo pins one stable Boot line and lets the BOM speak for everything else — no SNAPSHOTs anywhere in the tree.

                                The deeper question a pom must answer: WHERE do version numbers live? Scatter them on every dependency and the pom rots — thirty coordinates drift apart, and two libraries demand incompatible versions of a shared transitive. Maven has two centralization mechanisms, and this pom uses BOTH, deliberately:"""),
                        code("ver:parent", "xml",
                                "server/pom.xml — mechanism one: the parent BOM. Inherit spring-boot-starter-parent and every Boot-managed artifact becomes versionless",
                                "server/pom.xml", """
                                <parent>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-parent</artifactId>
                                    <version>4.1.1</version>
                                    <relativePath/>
                                </parent>
                                """),
                        text("ver:parent-tradeoff", """
                                The PARENT BOM (spring-boot-starter-parent) is inheritance: this project becomes a child of the Boot build, inheriting a curated dependencyManagement block covering hundreds of tested versions (Boot 4.1.1 pairs Spring Framework 7.0.9 with Jackson 3.1.5, Hibernate 7.4.5, Tomcat 11.0.24, Testcontainers 2.0.5 — one compatibility-tested set), plus plugin configuration and Java defaults. Its constraint is structural: single inheritance. You cannot also parent to your company's BOM, and versionless deps only work for artifacts the parent manages. When Spring ships something OUTSIDE the Boot BOM — as with the gRPC integration — the second mechanism applies: import a BOM into dependencyManagement, which ALIGNS a foreign ecosystem's versions without touching inheritance:"""),
                        code("ver:grpc-bom", "xml",
                                "server/pom.xml — mechanism two: the imported BOM (spring-grpc's own tested set) plus the enforcer-driven upper-bound pin for a transitive jar — two different jobs in one dependencyManagement block",
                                "server/pom.xml", """
                                <dependencyManagement>
                                    <dependencies>
                                        <dependency>
                                            <groupId>org.springframework.grpc</groupId>
                                            <artifactId>spring-grpc-dependencies</artifactId>
                                            <version>${spring-grpc.version}</version>
                                            <type>pom</type>
                                            <scope>import</scope>
                                        </dependency>
                                        <dependency>
                                            <groupId>com.google.errorprone</groupId>
                                            <artifactId>error_prone_annotations</artifactId>
                                            <version>2.41.0</version>
                                        </dependency>
                                    </dependencies>
                                </dependencyManagement>
                                """),
                        text("ver:props", """
                                The ${spring-grpc.version} property (1.0.3) is one of FOUR version-shaped properties in this pom — and one more literal number lives in the dependencyManagement block just quoted (the 2.41.0 error_prone pin, whose story the tree section below tells: the enforcer demanded it). The only places a number can drift apart are exactly these:

                                """),
                        code("ver:properties", "xml",
                                "server/pom.xml — the four properties: the codegen trio must stay mutually aligned (protoc, the protobuf runtime, and the grpc-java codegen binary are one version family)",
                                "server/pom.xml", """
                                <properties>
                                    <java.version>25</java.version>
                                    <protobuf-java.version>4.33.4</protobuf-java.version>
                                    <grpc.version>1.77.1</grpc.version>
                                    <spring-grpc.version>1.0.3</spring-grpc.version>
                                </properties>
                                """),
                        text("ver:scopes", """
                                SCOPES are architecture, not bookkeeping — they decide which jars exist at compile time, at runtime, and in tests, and the wrong scope is a design leak the build enforces or permits. COMPILE (the default) is on the classpath everywhere: spring-web, your domain code's foundation. RUNTIME ships in the artifact but is invisible to your source — this repo's sharpest example is the PostgreSQL driver, runtime ON PURPOSE so that no code can ever import org.postgresql and couple the domain to one database: compile code speaks java.sql, and the scope mechanically enforces the rule. TEST (JUnit, Mockito, AssertJ, the Testcontainers modules) never leaves the build — a production runtime that somehow loaded them would be a packaging bug made visible. PROVIDED expects the platform to supply it at runtime (servlet-API territory; Boot hides this one from you). And OPTIONAL (devtools below) means "my consumers probably don't want this transitively" — devtools is developer-experience tooling flagged optional so a deployed artifact does not drag it along:"""),
                        code("ver:scopes-pom", "xml",
                                "server/pom.xml — three scopes doing three different jobs: dev-experience isolation, test-only machinery, driver isolation",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-devtools</artifactId>
                                    <scope>runtime</scope>
                                    <optional>true</optional>
                                </dependency>
                                <dependency>
                                    <groupId>org.testcontainers</groupId>
                                    <artifactId>testcontainers-postgresql</artifactId>
                                    <scope>test</scope>
                                </dependency>
                                <dependency>
                                    <groupId>org.postgresql</groupId>
                                    <artifactId>postgresql</artifactId>
                                    <scope>runtime</scope>
                                </dependency>
                                """),
                        text("ver:renames", """
                                One Boot-4-specific hazard worth its own paragraph: RENAMED COORDINATES. The 1.x-to-2.x Testcontainers transition renamed junit-jupiter to testcontainers-junit-jupiter and postgresql to testcontainers-postgresql (the old names stop receiving versions), and Boot 4 renamed spring-boot-starter-aop to spring-boot-starter-aspectj. A rename makes the versionless trick DANGEROUS in the other direction: an outdated artifactId under a BOM simply resolves to nothing or to a dead line, and the failure surfaces as "dependency not found" or — worse — as a stale old artifact that still resolves. The defense is exactly what this pom does: when adopting an ecosystem shift, VERIFY THE COORDINATE on Central (the repo's pom comments record those verifications with dates), and let the BOM pick the version of the NEW name only.""")),
                section("the-tree-and-the-enforcer", "The tree and the enforcer",
                        "dependency:tree anatomy, how Maven actually resolves conflicts (nearest-wins, and what the BOM overrides), the enforcer plugin — and this repo's runtime-version manifest as a live drift guard.",
                        text("tree:anatomy", """
                                `mvn dependency:tree` prints the resolved graph — the single most useful command in dependency work. Each line is one resolved artifact with its VERSION and SCOPE; indentation shows who pulled whom. This repo's snapshot (the full 40-direct capture lives in the demo below and in server/src/main/resources/deps/dependency-tree-snapshot.txt) reads like a map of decisions — starters as trunks, drivers as leaves:

                                """),
                        code("tree:snapshot", "text",
                                "the checked-in snapshot (resources/deps/dependency-tree-snapshot.txt) — starter-web's subtree, the isolated driver, the pinned leaf",
                                null, """
                                +- org.springframework.boot:spring-boot-starter-web:jar:4.1.1:compile
                                |  +- org.springframework.boot:spring-boot-starter-jackson:jar:4.1.1:compile
                                |  +- org.springframework.boot:spring-boot-starter-tomcat:jar:4.1.1:compile
                                |  +- org.springframework.boot:spring-boot-http-converter:jar:4.1.1:compile
                                |  \\- org.springframework.boot:spring-boot-webmvc:jar:4.1.1:compile
                                +- org.postgresql:postgresql:jar:42.7.13:runtime
                                +- com.bucket4j:bucket4j_jdk17-core:jar:8.21.0:compile
                                \\- net.coobird:thumbnailator:jar:0.4.21:compile
                                """),
                        text("tree:mediation", """
                                CONFLICT RESOLUTION — the part everyone gets wrong once. When two branches of the graph want different versions of the same coordinate, Maven does NOT pick the newest. It applies NEAREST-WINS: the version declared CLOSEST to the root wins, purely by graph distance. This repo's tree has a real, current specimen: io.grpc:grpc-protobuf (depth 2) declares guava 33.4.8-android, while grpc-services (one level deeper, under grpc-netty) asks for the -jre flavor of the same 33.4.8. Mediation keeps -android — not because it is better, but because it is NEARER. Run `mvn dependency:tree -Dverbose` and maven prints the losers as "omitted for conflict with …" (the same run also shows the BOM's overrides — spring-web requested micrometer-observation 1.16.7 and the Boot BOM forced 1.17.1; dependencyManagement beats every transitive request, always). Nearest-wins surprises you when a SHALLOW declaration freezes a shared library while other branches silently run against a version they never asked for — the classic breakage is a NoSuchMethodError at runtime in code that compiled fine, because compile used one classpath and runtime another. Read the tree before blaming the framework.

                                The tree tells you what RESOLVED. To turn resolution rules into CI-enforced policy, the MAVEN ENFORCER PLUGIN is the standard tool — rules like requireUpperBoundDeps (fail when mediation kept an OLDER version than some branch requested), requireReleaseDeps (fail on SNAPSHOTs), banDuplicatePomDependencyVersions, and dependencyConvergence (fail on ANY conflict, forcing you to choose explicitly). This repo runs requireUpperBoundDeps on every validate, and the rule's FIRST run here is worth retelling because it produced exactly the right kind of argument. Six violations, three families. One family was REAL and got FIXED: gson (riding grpc-services) asked error_prone_annotations 2.41.0 while grpc-stub's nearer 2.36.0 won — an annotations-only jar has no runtime behavior to regress, so the pom pins the upper bound in dependencyManagement (quoted in the versions section above) and the violation is gone, not silenced. The other two families were the rule's known blind spots, EXCLUDED with the reason recorded beside the exclusion: the BOM-owned Jackson 2 line (the Boot BOM deliberately manages 2.21.5 against swagger's 2.22.1 request — dependencyManagement beating a transitive request is the BOM working as designed, not a defect), and the guava classifier pair (-android and -jre are the same 33.4.8 in two flavors, which the rule's version comparison misreads as a downgrade). The method is the lesson: an enforcer rule is a POLICY you tune — run it, read every violation, resolve what is real, exclude what is deliberate, and record why next to each exclusion:"""),
                        code("tree:enforcer-pom", "xml",
                                "server/pom.xml — the enforcer, live: requireUpperBoundDeps with its two recorded exclusion families (the real error_prone fix lives in dependencyManagement, quoted earlier)",
                                "server/pom.xml", """
                                <plugin>
                                    <groupId>org.apache.maven.plugins</groupId>
                                    <artifactId>maven-enforcer-plugin</artifactId>
                                    <executions>
                                        <execution>
                                            <id>enforce-upper-bound-deps</id>
                                            <goals>
                                                <goal>enforce</goal>
                                            </goals>
                                            <configuration>
                                                <rules>
                                                    <requireUpperBoundDeps>
                                                        <excludes>
                                                            <!-- … the BOM-owned Jackson 2 line: the Boot BOM manages 2.21.5 … -->
                                                            <exclude>com.fasterxml.jackson.core:jackson-databind</exclude>
                                                            <!-- … the guava classifier pair: same 33.4.8, two flavors … -->
                                                            <exclude>com.google.guava:guava</exclude>
                                                        </excludes>
                                                    </requireUpperBoundDeps>
                                                </rules>
                                            </configuration>
                                        </execution>
                                    </executions>
                                </plugin>
                                """),
                        text("tree:enforcer-complement", """
                                The enforcer polices the BUILD-TIME graph; this repo pairs it with a different, complementary guard: the manifest below reads versions from the RUNTIME itself, and tests pin them — not to freeze the numbers (the BOM bumps them constantly, which is the point of a BOM) but to catch a resolution change that silently alters what the demo and the teaching claims describe."""),
                        demo("tree:demo", "deps:manifest",
                                "The live dependency manifest",
                                "GET /api/demo/deps/manifest reads the version of every curated artifact from the class that is actually loaded — manifest metadata first, jar-name fallback second — beside the flagged-choices registry. GET /api/demo/deps/tree serves the summarized dependency:tree snapshot with its recorded mediation conflicts."),
                        text("tree:probe", """
                                The manifest endpoint's reading method is worth quoting because it is honest machinery: Package.getImplementationVersion() asks the JVM what the jar's MANIFEST.MF claims — and when a jar ships no manifest version at all (real cases in this very tree: HikariCP and flyway-core), the fallback parses the version out of the code-source jar's FILE NAME, which works both in tests (plain ~/.m2 jars) and inside the deployed Boot jar (BOOT-INF/lib keeps artifactId-version.jar naming):"""),
                        code("tree:probe-code", "java",
                                "guide/data/deps/RuntimeVersionProbe.java — the two-tier version read: manifest claim first, jar-name fallback second",
                                "server/src/main/java/guide/data/deps/RuntimeVersionProbe.java", """
                                @Override
                                public ProbedClass probe(String probeClass) {
                                    Class<?> loaded;
                                    try {
                                        // initialize=false: reading metadata must not run static blocks
                                        loaded = Class.forName(probeClass, false, RuntimeVersionProbe.class.getClassLoader());
                                    } catch (ClassNotFoundException e) {
                                        return new ProbedClass(probeClass, false, null, null);
                                    }
                                    String manifestVersion = loaded.getPackage().getImplementationVersion();
                                    if (manifestVersion != null && !manifestVersion.isBlank()) {
                                        return new ProbedClass(probeClass, true, manifestVersion, "manifest");
                                    }
                                    return fromJarName(loaded, probeClass);
                                }
                                """),
                        text("tree:drift", """
                                The same philosophy guards the FLAGGED pins — the five dependencies that carry explicit versions because no BOM manages them. Their rationales live in FlaggedDecisions (the domain registry the manifest serves), and a sync test parses the REAL pom and fails in BOTH directions: a new pin without a registered WHY fails the suite, and a registry entry whose pin vanished from the pom fails it too. Prose comments rot silently; presence-or-absence is executable:

                                """),
                        code("tree:sync-test", "java",
                                "server/src/test/java/guide/domain/deps/FlaggedDecisionsPomSyncTest.java — the drift guard: pom and registry must agree, mechanically",
                                "server/src/test/java/guide/domain/deps/FlaggedDecisionsPomSyncTest.java", """
                                Set<String> pinned = pinnedPomCoordinatesWithVersion();

                                assertTrue(pinned.containsAll(registry),
                                        () -> "registry entries missing from the pom's pinned dependencies: "
                                                + difference(registry, pinned));
                                assertTrue(registry.containsAll(pinned),
                                        () -> "pom pins a version with NO FlaggedDecisions entry (write the WHY): "
                                                + difference(pinned, registry));
                                """),
                        text("tree:wire", """
                                The CLIENT side of the demo is part of the same honesty: the manifest's wire shape drops null fields (an absent test-scoped jar has NO version key at all — absent is not zero, not "", but missing), so the demo's parser treats a missing key as the null it means and everything else as a contract violation that renders the error panel instead of a half-true table:

                                """),
                        code("tree:wire-parse", "ts",
                                "client/src/ui/demos/deps/manifest_demo.tsx — the wire contract on the reading side: version is string-or-absent, never guessed",
                                "client/src/ui/demos/deps/manifest_demo.tsx", """
                                function optionalString(source: Record<string, unknown>, key: string): string | null {
                                  const value = source[key];
                                  if (value === undefined) return null;
                                  if (typeof value !== 'string') throw new Error(`field "${key}" must be a string when present`);
                                  return value;
                                }
                                // …
                                function parseManifestBody(source: unknown): ManifestReport {
                                  if (!isRecord(source)) throw new Error('manifest report must be an object');
                                  const artifacts = source.artifacts;
                                  const flagged = source.flaggedDecisions;
                                  if (!Array.isArray(artifacts)) throw new Error('field "artifacts" must be an array');
                                  if (!Array.isArray(flagged)) throw new Error('field "flaggedDecisions" must be an array');
                                  return { artifacts: artifacts.map(parseArtifact), flaggedDecisions: flagged.map(parseFlagged) };
                                }
                                """)),
                section("upgrades-without-fear", "Upgrades without fear",
                        "The versions plugin as radar, the cadence rubric (security now, minors batched, majors planned), and the CI suite as the safety net that makes upgrades boring.",
                        text("up:radar", """
                                The scariest thing about dependencies is not upgrading — it is NOT KNOWING what is outdated. The radar is the VERSIONS MAVEN PLUGIN: `mvn versions:display-dependency-updates` lists every dependency with a newer available release (and `versions:display-property-updates` does the same for the version properties; `versions:display-plugin-updates` for build plugins), against the repositories your build actually uses. It is a REPORTING tool, not an action tool — pair it with `versions:commit` only when you mean it (the plugin can rewrite the pom; this repo's discipline is read the report, edit deliberately, keep the flag comments intact). For a curated outside-the-BOM view, Dependabot/Renovate open versioned pull requests — each one exercising the safety net below on every candidate.

                                The cadence rubric this repo teaches is triage by risk class. SECURITY PATCHES: apply immediately — a CVE in a dependency you expose is someone else's bug becoming your incident, and "we'll take it in the quarterly batch" is how Equifax happened (one unpatched Struts jar). This is also why versionless-through-the-BOM matters operationally: bump the Boot parent property and dozens of patched transitives arrive as ONE reviewed change. MINORS: batch them on a rhythm (weeks, not days — let early adopters find the regressions; not quarters — debt compounds), reading release notes for behavior flags, not just API. MAJORS: plan like a feature — a migration branch, the full suite, extra soak time; this repo's Boot 3-to-4 jump (renamed starters, Jackson 3's package move, modularized test slices — all documented in earlier topics) is exactly the size of project a major is. The anti-pattern is the FROZEN pom that "never breaks": it does not avoid the migration, it schedules the migration for the WORST possible moment — during the emergency that finally forces it."""),
                        text("up:ci", """
                                What makes any of this safe is the SAFETY NET: a fast, honest, comprehensive suite that fails loudly when an upgrade changes behavior. That is this guide's whole testing investment paying rent — 900+ server tests including real-PostgreSQL integration (Testcontainers), wire-shape assertions on every demo endpoint, and snippet verification that PINS the teaching to real source files. An upgrade that changes an endpoint's shape, a dependency's loaded version, or a quoted file's contents breaks a named test in CI, not an unnamed user in production. CI is also where the maven cache keeps upgrades cheap — the runner restores ~/.m2 from the pom hash, so a version bump downloads only the delta:

                                """),
                        code("up:ci-yaml", "yaml",
                                ".github/workflows/ci.yml — cache keyed on the pom: an upgrade re-downloads exactly what changed, and the suite (not hope) gates the merge",
                                ".github/workflows/ci.yml", """
                                - uses: actions/setup-java@v4
                                  with:
                                    distribution: temurin
                                    java-version: 25
                                    cache: maven
                                    cache-dependency-path: server/pom.xml
                                - run: mvn -B test
                                """),
                        text("up:rollback", """
                                One more upgrade mechanic that earns its keep: ROLLBACK IS A DEPENDENCY PROPERTY. Because the artifact is immutable and versioned (Central never overwrites a release), reverting is re-pointing the pom at the old coordinate and re-deploying — minutes, not archaeology. That is precisely what SNAPSHOTs destroy (a re-built snapshot cannot be rolled back to, because the bytes changed under the same version), and why the versions-scopes section's radioactivity rule exists. Upgrades without fear is not a feeling; it is radar (versions plugin), rhythm (the rubric), a net (the suite in CI), and an undo (immutable versions).""")),
                section("docker-and-dependency-caching", "Docker and dependency caching",
                        "The layered jar dissected: why layer COPY order is cache economics, and how CI keeps the Maven repository warm.",
                        text("docker:layers", """
                                Server image builds have a brutal asymmetry: your code changes constantly, your dependencies almost never — and a naive Dockerfile that COPYs the source and runs the build re-downloads and re-copies two hundred jars on EVERY commit. Spring Boot's answer is the LAYERED JAR: instead of one opaque fat jar, the build can EXTRACT the same bytes into four directories ordered by change frequency — dependencies (the versioned third-party jars, changes only on upgrades), spring-boot-loader (the launcher), snapshot-dependencies (SNAPSHOT jars, expected to churn), and application (YOUR classes — the hot layer). Each directory becomes its own Docker layer; a layer is rebuilt only when its content changes. The prod image build runs the extraction explicitly:

                                """),
                        code("docker:extract", "dockerfile",
                                ".docker/Dockerfile.server.prod — the build stage: package once, then extract the fat jar into its four layer directories",
                                ".docker/Dockerfile.server.prod", """
                                RUN mvn -q -DskipTests package \\
                                 && java -Djarmode=tools -jar target/*.jar extract --layers --destination target/extracted
                                """),
                        code("docker:copy-order", "dockerfile",
                                ".docker/Dockerfile.server.prod — the COPY order IS the cache: least-changed first, application last",
                                ".docker/Dockerfile.server.prod", """
                                COPY --from=build /srv/target/extracted/dependencies/ ./
                                COPY --from=build /srv/target/extracted/spring-boot-loader/ ./
                                COPY --from=build /srv/target/extracted/snapshot-dependencies/ ./
                                COPY --from=build /srv/target/extracted/application/ ./
                                """),
                        text("docker:economics", """
                                Read that COPY order as CACHE ECONOMICS. Docker invalidates a layer — and every layer AFTER it — when its inputs change. Dependencies first: unchanged across a hundred commits, so those layers are cache hits and a rebuild of changed application code skips megabytes of copying (on CI: skips re-pushing them to the registry, which is where deploy latency actually goes). Application LAST: it changes every build, and nothing sits below it to invalidate. Flip the order (application first) and every commit rebuilds the dependency layers too — the two-minute image becomes the ten-minute image and the fix is three lines. The same economics govern the DEV loop: this repo's verification runs mount a named Docker volume as the container's ~/.m2 (sbdg-m2) so a fresh `docker run` starts with a warm repository — downloads happen once, ever — and the devcontainer bakes the Maven cache into its server image layer for the same reason. CI's setup-java `cache: maven` (quoted in the upgrades section) is the same trick with a different storage backend, keyed on the pom hash: change one dependency, download one jar.

                                One subtlety worth naming: the layered jar changes nothing about RUNTIME classloading — the Boot launcher (that spring-boot-loader layer) puts the directories back together into one classpath at startup. Layers are purely a PACKAGING decision that plays nicely with Docker's invalidation model; the JVM sees the same fat jar it always did."""),
                        text("docker:snapshots", """
                                And the snapshot-dependencies layer quietly reinforces an earlier lesson: it is SEPARATE precisely because SNAPSHOT jars churn without version changes — Docker cannot detect "a snapshot re-published under the same version" as a content change unless the layer containing it is expected to move. Stable releases make that layer boring (it stays empty), which is the dependency-management story in miniature: good version hygiene is not just risk management, it makes every piece of delivery machinery — caches, layers, rollbacks — faster and more predictable.""")),
                section("choosing-dependencies", "Choosing dependencies",
                        "The rubric — maintained? transitive weight? license? escape hatch? — and this repo's own decision log as case studies.",
                        text("choose:rubric", """
                                Adding a dependency is a decision with a decade of consequences; the rubric this repo applies has four questions. MAINTAINED? — commit activity, release cadence, an issues tracker where humans answer; a library "done" because it is stable is fine (Thumbnailator), a library dead while its ecosystem moves (imgscalr, dormant since 2019, passed over for exactly that) is a trap. TRANSITIVE WEIGHT? — run the tree BEFORE adopting: one convenient jar pulling forty transitive ones (looking at you, enterprise SDKs) imports a maintenance surface you did not budget for; the grpc-protobuf guava conflict in section three is the kind of surprise weight brings. LICENSE? — permissive (MIT/Apache) is invisible; anything copyleft (GPL family) in a shipped product is a legal question, not a developer one — ask before the dependency is load-bearing. ESCAPE HATCH? — if this library died tomorrow, what is the exit: is the capability a thin wrapper over a standard (replaceable), or does it own your data and call sites (a rewrite)? Prefer the wrapper you could delete.

                                The repo's decision log, as cases. THUMBNAILATOR (topic 26): small, focused, single jar, no transitive dependencies at all (the tree above shows it as a bare leaf — `net.coobird:thumbnailator:jar:0.4.21` with nothing under it), no native code, fluent API — chosen over a dormant rival and over hand-rolled java.awt code that would have been MORE code to own for worse results. BUCKET4J (topic 25): the case of ACCEPTED FRICTION — no first-party Boot starter exists, the only ecosystem starter is third-party and Boot-3-shaped, so the choice was the framework-agnostic core jar wired by hand. That costs an explicit @Bean instead of properties (friction), and buys zero Spring coupling — the library cannot be broken by a Boot major, because it never knew Boot existed (escape hatch by construction):"""),
                        code("choose:bucket4j", "xml",
                                "server/pom.xml — the bucket4j pin: framework-agnostic core, no starter exists and the third-party one was rejected on Boot-version grounds",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>com.bucket4j</groupId>
                                    <artifactId>bucket4j_jdk17-core</artifactId>
                                    <version>8.21.0</version>
                                </dependency>
                                """),
                        text("choose:renames", """
                                The RENAME cases are the ecosystem-churn survival kit. When Boot 4 renamed spring-boot-starter-aop to spring-boot-starter-aspectj, the fix was mechanical — but FINDING it required reading release notes and verifying the new coordinate on Central, and the pom comment records the rename so the next reader is not confused by guides that still say "starter-aop":

                                """),
                        code("choose:aspectj", "xml",
                                "server/pom.xml — the renamed starter: Boot 4's aspectj name for what every older guide still calls starter-aop",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-aspectj</artifactId>
                                </dependency>
                                """),
                        text("choose:testcontainers", """
                                TESTCONTAINERS 2.x (topic 18's foundation) is the managed-migration case: the artifacts were RENAMED (junit-jupiter → testcontainers-junit-jupiter, postgresql → testcontainers-postgresql, mongodb → testcontainers-mongodb — old names stop at 1.21.x), yet the pom stays VERSIONLESS because the Boot BOM imports testcontainers-bom 2.0.5 — the rename was an artifactId change, and once the new names are in place, the BOM owns the numbers again. The general lesson: let the BOM carry version churn where a BOM exists; carry explicit pins (with recorded rationale) only where none does — springdoc, bucket4j, thumbnailator, the logstash encoder, and resilience4j are the five, and the flagged registry keeps them honest. Every one of those rationales ships in the manifest demo above, served from the same registry the sync test guards:

                                """),
                        code("choose:registry", "java",
                                "guide/domain/deps/FlaggedDecisions.java — the decision log as data: why each non-managed pin exists, which topic owns it",
                                "server/src/main/java/guide/domain/deps/FlaggedDecisions.java", """
                                new FlaggedDecision("net.coobird", "thumbnailator", "0.4.21",
                                        "The single-jar image-resizing library, chosen over imgscalr "
                                                + "(dormant since 2019) and hand-rolled java.awt scaling: "
                                                + "fluent API over BufferedImage, EXIF orientation, aspect-"
                                                + "preserving bounding box, no native dependencies, no "
                                                + "framework coupling. No Boot starter exists and none is "
                                                + "wanted — it is a plain library call inside the "
                                                + "CreateThumbnail use case.",
                                        "26 files-media"),
                                // … later topics append their pins the same way
                                """),
                        text("choose:say-no", """
                                The rubric's last move is SAYING NO. "It's just one small dependency" is how the 40-direct pom became the ecosystem-normal 300-direct pom; the review question for every new coordinate is not "does it work" but "what does it cost for the next five years, and is there no standard-platform answer already on the classpath?" — Spring's own starters cover a remarkable amount of ground before you ever visit Central.""")),
                section("terminal-companion", "Terminal companion",
                        "curl the manifest, walk the tree, interrogate updates — the dependency commands worth memorizing.",
                        text("term:intro", """
                                Every technique in this topic is one command away. The demo endpoints first — the manifest is the runtime truth (note the versionSource field: manifest vs jar-name is part of the answer), the tree serves the labeled snapshot with its recorded conflicts:

                                """),
                        code("term:curl-manifest", "bash",
                                "the live manifest: curated artifacts with runtime-resolved versions + the flagged registry",
                                null, """
                                # runtime truth for the key artifacts (jq optional — the raw JSON reads fine)
                                curl -s localhost:8080/api/demo/deps/manifest | head -40

                                # one artifact's row, version included: grep beats jq when jq is absent
                                curl -s localhost:8080/api/demo/deps/manifest | grep -A 3 '"artifactId": "grpc-stub"'

                                # the summarized tree snapshot + its mediation conflicts
                                curl -s localhost:8080/api/demo/deps/tree | head -30
                                """),
                        code("term:tree-commands", "bash",
                                "the maven side: tree, verbose conflicts, the enforcer, and the update radar",
                                null, """
                                # the resolved graph (this repo runs builds in docker — see the DoD)
                                docker run --rm -v "$PWD":/repo -w /repo/server -v sbdg-m2:/root/.m2 \\
                                  maven:3.9-eclipse-temurin-25 mvn -B dependency:tree | tee /tmp/tree.txt

                                # conflicts + BOM overrides — the losers are printed as "omitted"
                                mvn -B dependency:tree -Dverbose | grep -E "omitted for conflict|version managed from" | sort -u

                                # the build-time policy: validate runs requireUpperBoundDeps (the enforcer)
                                mvn -B validate

                                # who pulls THIS jar? walk the tree upwards with grep -B
                                grep -B 4 "jackson-databind" /tmp/tree.txt

                                # the upgrade radar: dependency, property, and plugin updates
                                mvn versions:display-dependency-updates
                                mvn versions:display-property-updates
                                mvn versions:display-plugin-updates
                                """),
                        text("term:closing", """
                                Two habits to keep. First, when anything in dependency land surprises you — a NoSuchMethodError, a version that is not what the pom says, a jar you do not recognize — the ORDER is always the same: `dependency:tree` (what resolved), the manifest endpoint (what actually loaded), THEN the code. Second, when you change the pom, change it the way this repo models: versionless where a BOM owns the number, a flagged pin with a recorded WHY where none does, and the suite — snippet checks, sync test, drift pins — watching that the story stays true.""")));
    }
}

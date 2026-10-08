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
 * Topic 36 — Environments. Group platform, order 7. One codebase promoted
 * through many environments is the whole discipline: what varies (config,
 * infrastructure), what must not (the build), and the machinery Spring gives
 * each layer of the question — twelve-factor env vars with readable
 * defaults, ONE typed @ConfigurationProperties record (made real here as
 * guide.core.AppConfig, inspected live by the env:inspector demo with a
 * structurally-redacted secret), profiles as named configuration variants,
 * behavior toggles that ride env vars, secrets handled beyond .env files,
 * and feature flags as the one kind of "config" that changes while the
 * process runs (this app's own ScenarioStore).
 */
public final class EnvironmentsTopic {

    private EnvironmentsTopic() {
    }

    public static Topic topic() {
        return new Topic("environments", "Environments",
                "One build, promoted from laptop to compose to prod: what varies (config, "
                        + "infrastructure) and what must never (the artifact). The machinery for "
                        + "each layer — twelve-factor env vars with readable defaults, one typed "
                        + "@ConfigurationProperties record inspected live (secrets redacted by "
                        + "construction), profiles as named config variants, env-specific behavior, "
                        + "secrets beyond .env, and feature flags as the config that changes while "
                        + "the process runs.",
                TopicGroupId.PLATFORM, 7,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("one-codebase-many-environments", "One codebase, many environments",
                        "The same artifact must run on your laptop, in compose, and in production — differing only by what the environment injects. Everything in this topic defends that one sentence.",
                        text("oc:problem", """
                                "Works on my machine" is not a joke, it is a DIAGNOSIS: the code was fine and the ENVIRONMENT was the variable. Every real application runs in several environments — local (your laptop), shared dev or staging (where product and teammates see it), CI (where tests run against ephemeral copies), and production — and the failure mode this whole topic exists to prevent is the environment DIFFERENCE you only discover at deploy time: the datasource URL that was baked in, the memory of a file someone copied by hand, the flag that is on in prod because someone edited the yaml there and never told the repo. The discipline has one sentence at its center: THE BUILD IS IDENTICAL EVERYWHERE; THE ENVIRONMENT SUPPLIES THE DIFFERENCE. Same jar, same image, same digest — what changes between laptop and prod is what the process READS at startup: environment variables, mounted config, connected infrastructure.

                                Notice what that rule OUTLAWS: an artifact rebuilt per environment (the prod build with the prod URL compiled in is already a different program than the one you tested), a hand-edited config file INSIDE the container (the drift the next deploy silently reverts), and the "quick fix" applied over SSH (unreproducible by definition). The deployment topic takes the shipping side of this; the environments topic takes the CONFIG side: how a Spring Boot process is built so that every environment-specific fact ARRIVES from outside, typed, validated, and inspectable.

                                This repo is its own best exhibit, because it genuinely runs in three worlds off one build. The dev world is `.docker/docker-compose.yml`: the server container runs from the bind-mounted source with devtools, and its environment block points every service at its compose sibling. The prod world is `.docker/docker-compose.prod.yml`: the SAME source, but built into a layered image once — and its environment carries only the facts that differ (SPRING_PROFILES_ACTIVE=prod names the posture outright, and the hardening rails made SDG_JWT_SECRET and the database password compose-REQUIRED — the `${VAR:?…}` syntax refuses to render the stack at all without them, and SDG_DB_PASSWORD feeds BOTH the app's datasource password and the postgres service's POSTGRES_PASSWORD, so the database and the app cannot disagree; a missing value fails the deploy, not the first request):"""),
                        code("oc:prod-compose", "yaml",
                                ".docker/docker-compose.prod.yml — the prod stack: same repo, built once, only the environment differs",
                                ".docker/docker-compose.prod.yml", """
                                server:
                                  build:
                                    context: ./../
                                    dockerfile: ./.docker/Dockerfile.server.prod
                                  image: sbdg_server_prod
                                  # … ports, restart, depends_on
                                  environment:
                                    - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/guide
                                    - SPRING_DATASOURCE_USERNAME=guide
                                    - SPRING_DATASOURCE_PASSWORD=${SDG_DB_PASSWORD:?SDG_DB_PASSWORD must be set to a real database password}
                                    - SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/guide
                                    - SPRING_DATA_REDIS_HOST=redis"""),
                        text("oc:devcontainer", """
                                The third world is the one you are probably sitting in: the devcontainer. It deserves the phrase ENVIRONMENT AS CODE — the full development world (the tooling container you attach to, the server and client dev servers, postgres, redis, mongo, the volumes and healthchecks) is a versioned file in the repo, so "set up the dev environment" is `git clone` plus one command, and a new teammate's machine matches yours by CONSTRUCTION rather than by luck. The same honesty applies as to the app config: when the dev environment needs to change, the file changes, in review, like any other code:"""),
                        code("oc:devcontainer-json", "json",
                                ".devcontainer/devcontainer.json — the dev environment is a versioned file, not a wiki page",
                                ".devcontainer/devcontainer.json", """
                                {
                                  "name": "springboot-developers-guide",
                                  "dockerComposeFile": "../.docker/docker-compose.yml",
                                  "service": "dev",
                                  "runServices": ["dev", "server", "client", "postgres", "redis", "mongo"],"""),
                        text("oc:what-varies", """
                                So what may vary between environments, and what may not? MAY VARY: endpoints and credentials of backing services (datasource, redis, mongo URIs), infrastructure topology (localhost versus compose service names), feature posture (GraphiQL mounted in dev only, TLS-aware cookie flags in prod), resource sizing (pool sizes, heap), and observability intensity (trace sampling). MUST NOT VARY: the compiled code, the schema migrations, the API contract, the security RULES (a rule that exists only in prod is a vulnerability with extra steps — the security topic's CSP and headers are the same in every environment, only HSTS emission honestly differs because TLS itself differs). Parity is a dial, not a religion: you will not run prod's full topology locally, but every difference you ACCEPT should be a difference you can NAME — which is exactly what the rest of this topic builds: a machinery where each environment-specific fact has one declared home.""")),
                section("twelve-factor-config", "Twelve-factor: config lives in the environment",
                        "Factor III in practice: externalized config, env-var overrides, readable dev defaults, and failing at boot when the environment is wrong — quoted from this repo's own datasource wiring.",
                        text("tf:principle", """
                                The twelve-factor manifesto's third factor — CONFIG IN THE ENVIRONMENT — is the oldest and most load-bearing of the rules this topic leans on: everything that differs between deployments lives in the ENVIRONMENT, not in the code. Spring speaks this natively through PropertySources and RELAXED BINDING: the canonical property `spring.datasource.url` is settable as the environment variable `SPRING_DATASOURCE_URL` (dots become underscores, case normalizes), which is why the compose file's `environment:` block reads like a flat config file. The other half of the idiom is the PLACEHOLDER WITH DEFAULT: `${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/guide}` reads "environment first, this literal second" — one line that carries BOTH the production override path AND the documented localhost dev default. This repo's own yaml is the pattern, quoted for real:"""),
                        code("tf:datasource", "yaml",
                                "server/src/main/resources/application.yml — the datasource: every value an env override with a readable dev default",
                                "server/src/main/resources/application.yml", """
                                  datasource:
                                    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/guide}
                                    username: ${SPRING_DATASOURCE_USERNAME:guide}
                                    password: ${SPRING_DATASOURCE_PASSWORD:guide}
                                    # … hikari pool sizing comment
                                    hikari:
                                      maximum-pool-size: 8"""),
                        text("tf:compose-wiring", """
                                Read that block twice and notice what it does NOT contain: no `@prod` URL, no branch, no build profile — the yaml works unchanged on a laptop (postgres on localhost) and inside compose, because compose's job is to set the SAME environment variables to sibling-service values. The dev compose file is the other half of the quote — the same three properties, pointed at the compose network's service names (plus the two later topics added the same way), and one deliberate new line this topic teaches with: GUIDE_DEPLOY_ENV, which the inspector demo below reports so you can SEE the override happen:"""),
                        code("tf:compose-env", "yaml",
                                ".docker/docker-compose.yml — compose sets the same env vars to compose values; the app never learns compose exists",
                                ".docker/docker-compose.yml", """
                                    environment:
                                      - SPRING_PROFILES_ACTIVE=dev
                                      - GUIDE_DEPLOY_ENV=dev
                                      # … the deliberate topic-36 override line, flagged in the file
                                      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/guide
                                      - SPRING_DATASOURCE_USERNAME=guide
                                      - SPRING_DATASOURCE_PASSWORD=guide
                                      # … testcontainers override
                                      - SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/guide
                                      - SPRING_DATA_REDIS_HOST=redis"""),
                        text("tf:fail-fast", """
                                Env vars have an honest weakness: they are STRINGS, invisible to the compiler, and a typo (`fakse`) sails through binding until the first request explodes. The cure is the same rule the error-handling topic preaches, applied at startup: VALIDATE AT BOOT, FAIL FAST. A process that boots misconfigured has not "recovered" — it is a time bomb holding a port. This repo's oldest env-reader still shows the pattern in miniature: the scenario store's initial state comes from two raw env strings, parsed and validated ONCE, at bean-creation time — a bad SCENARIO_DATA_MODE kills the startup with a message that names the variable, the bad value, and the legal values:"""),
                        code("tf:scenario-bean", "java",
                                "guide/config/GuideConfig.java — the env read happens once, at the composition root, at boot",
                                "server/src/main/java/guide/config/GuideConfig.java", """
                                    @Bean
                                    ScenarioStore scenarioStore(Environment environment) {
                                        return new ScenarioStore(ScenarioState.fromEnvironment(
                                                environment.getProperty("SCENARIO_DATA_MODE"),
                                                environment.getProperty("SCENARIO_INJECT_FAILURE")));
                                    }"""),
                        code("tf:scenario-parse", "java",
                                "guide/core/scenario/ScenarioState.java — null-safe defaults, then validation that fails the boot, not the first request",
                                "server/src/main/java/guide/core/scenario/ScenarioState.java", """
                                    public static ScenarioState fromEnvironment(String rawDataMode, String rawInjectFailure) {
                                        return new ScenarioState(parseDataMode(rawDataMode), parseInjectFailure(rawInjectFailure));
                                    }

                                    private static ScenarioDataMode parseDataMode(String raw) {
                                        String value = raw == null || raw.isEmpty() ? ScenarioDataMode.FAKE.wireName() : raw;
                                        try {
                                            return ScenarioDataMode.fromWire(value);
                                        } catch (IllegalArgumentException e) {
                                            throw new IllegalStateException(
                                                    "Invalid SCENARIO_DATA_MODE \\"" + value + "\\". Expected one of: fake, live.", e);
                                        }
                                    }"""),
                        text("tf:verdict", """
                                Two honest caveats close the section. First, env vars are a DELIVERY mechanism, not a validation strategy — strings all the way down, so every read needs the parse-and-validate seam above (the next section moves that seam into the TYPE SYSTEM, where the compiler and the IDE can help). Second, env vars leak: `printenv` in a container, a debug endpoint that dumps the Environment, a stack trace that includes args — the secrets section handles that with redaction rules instead of hope. And a note on ordering: property sources have PRECEDENCE (command-line args beat env vars beat profile documents beats the base yaml), and when you cannot remember the order, you can always ASK the running process — the actuator's `env` endpoint exists for exactly that question, and the terminal companion shows both the asking and the caution it deserves.""")),
                section("typed-config-configuration-properties", "@ConfigurationProperties: one typed record",
                        "Scattered @Value needles into one constructor-bound record: typed, defaulted, validated at boot — and inspected live, with the secret structurally redacted.",
                        text("cp:motivation", """
                                The spring-core topic ended its property section with a promise: related configuration belongs in ONE @ConfigurationProperties record — typed, validated, documented — rather than a dozen @Value needles scattered wherever the values are used. This is the topic that cashes the check. The disease is familiar: `@Value("${guide.env.region}")` in one controller, `@Value("${guide.env.maintenance-mode}")` in a filter, each with its own default string duplicated in a second place, no shared validation, no type beyond String, and an IDE that cannot tell you what exists. The record is the cure — ONE type, constructor-bound by Boot from the `guide.env.*` property sources, normalizing and validating in its compact constructor so a bad value fails the STARTUP. This topic adds the app's first real one:"""),
                        code("cp:app-config", "java",
                                "guide/core/AppConfig.java — the app's typed config: defaults in the type, validation at boot, redaction support built in",
                                "server/src/main/java/guide/core/AppConfig.java", """
                                @ConfigurationProperties(prefix = "guide.env")
                                public record AppConfig(
                                        String deployEnvironment,
                                        String region,
                                        boolean maintenanceMode,
                                        String demoApiKey) {

                                    /** The loudly-fake default — anything equal to this is demo material, not a secret. */
                                    public static final String DEV_DEFAULT_API_KEY = "demo-only-not-a-real-secret";

                                    // … KNOWN_ENVIRONMENTS: the closed set a deploy environment may take

                                    public AppConfig {
                                        deployEnvironment = deployEnvironment == null || deployEnvironment.isBlank()
                                                ? "local"
                                                : deployEnvironment.trim().toLowerCase(Locale.ROOT);
                                        if (!KNOWN_ENVIRONMENTS.contains(deployEnvironment)) {
                                            throw new IllegalStateException("Unknown guide.env.deploy-environment \\"" + deployEnvironment
                                                    + "\\" — expected one of " + KNOWN_ENVIRONMENTS
                                                    + " (invalid config fails the boot, not the first request)");
                                        }
                                        region = region == null || region.isBlank() ? "local" : region.trim();
                                        demoApiKey = demoApiKey == null || demoApiKey.isBlank() ? DEV_DEFAULT_API_KEY : demoApiKey;
                                    }

                                    public boolean demoApiKeyIsDevDefault() {
                                        return DEV_DEFAULT_API_KEY.equals(demoApiKey);
                                    }
                                }"""),
                        text("cp:binding", """
                                Everything about that record teaches. The PREFIX maps kebab-case yaml (`guide.env.deploy-environment`) onto the constructor — relaxed binding again. The compact constructor is the validation seam from last section, but now the compiler checks you CALLED it, and the record's accessors carry the TYPES (maintenanceMode arrives as boolean, not as a string you parse at six call sites). The loudly-named DEV_DEFAULT_API_KEY makes the "clearly-marked dev default" discipline machine-checkable — `demoApiKeyIsDevDefault()` answers the only question a monitoring surface may ask about a secret WITHOUT ever moving its value. Registration happens where all wiring happens, the composition root — one annotation on the topic's demo config, one bean for the use case, and the active profiles read from the real Environment exactly once, at the Spring boundary, then handed to the domain as a plain value:"""),
                        code("cp:demo-config", "java",
                                "guide/config/EnvironmentsDemoConfig.java — @EnableConfigurationProperties at the composition root; Spring knowledge stays in config/",
                                "server/src/main/java/guide/config/EnvironmentsDemoConfig.java", """
                                @Configuration
                                @EnableConfigurationProperties(AppConfig.class)
                                public class EnvironmentsDemoConfig {

                                    @Bean
                                    ActiveProfiles activeProfiles(Environment environment) {
                                        return new ActiveProfiles(List.of(environment.getActiveProfiles()));
                                    }

                                    @Bean
                                    InspectEnvironment inspectEnvironment(AppConfig appConfig, ActiveProfiles activeProfiles,
                                            ScenarioStore scenarioStore) {
                                        return new InspectEnvironment(appConfig, activeProfiles, scenarioStore);
                                    }
                                }"""),
                        demo("cp:inspector-demo", "env:inspector", "Inspect the environment, live",
                                "The running process answers for its own configuration: active profiles (and what 'default' really means), the curated guide.env.* values from the typed record — each classified safe or SECRET, the secret redacted by construction with only the dev-default verdict traveling — and the runtime-switchable flags beside them. Flip data-mode and re-inspect: config is boot-shaped, flags are runtime-shaped, and the panel shows the difference in one click."),
                        text("cp:inspector-design", """
                                The inspector's design rule is the section's second lesson: a surface that reports configuration must REDACT BY CONSTRUCTION, not by filtering afterwards. The secret property line never receives the value — the use case builds it from a REDACTED constant, and the only fact that escapes about the key is the boolean verdict. Notice what that verdict buys operationally: "prod forgot to set GUIDE_DEMO_API_KEY" is visible AT A GLANCE while a real key stays unseen — the shape of the secret is public, its value is nowhere:"""),
                        code("cp:inspect", "java",
                                "guide/domain/environments/InspectEnvironment.java — safe values echo, the secret's value has NO path into the answer",
                                "server/src/main/java/guide/domain/environments/InspectEnvironment.java", """
                                List<EnvInspection.PropertyLine> properties = List.of(
                                        new EnvInspection.PropertyLine("guide.env.deploy-environment",
                                                config.deployEnvironment(), SAFE),
                                        new EnvInspection.PropertyLine("guide.env.region", config.region(), SAFE),
                                        new EnvInspection.PropertyLine("guide.env.maintenance-mode",
                                                String.valueOf(config.maintenanceMode()), SAFE),
                                        new EnvInspection.PropertyLine("guide.env.demo-api-key", REDACTED, SECRET));
                                boolean devDefault = config.demoApiKeyIsDevDefault();"""),
                        code("cp:client", "ts",
                                "client/src/ui/demos/environments/env_inspector_demo.tsx — flip the runtime flag, then re-inspect: two rails on one screen",
                                "client/src/ui/demos/environments/env_inspector_demo.tsx", """
                                /** Flip the runtime flag, then re-inspect: config is boot-shaped, flags are runtime-shaped. */
                                const flipDataMode = () => {
                                  const current = inspection?.flags.find((flag) => flag.name === 'scenario.data-mode');
                                  const next = current?.state === 'live' ? 'fake' : 'live';
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () =>
                                    requestJson(config, '/demo/scenario', {
                                      method: 'PATCH',
                                      body: JSON.stringify({ dataMode: next }),
                                    }),
                                  ).then((result) => {
                                    if (result.ok) {
                                      loadInspection();
                                    } else {
                                      // the typed envelope (e.g. an unknown mode) stays visible
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                };"""),
                        text("cp:verdict", """
                                Where does @Value still earn a place? The rule from spring-core stands: ONE lone injected constant — the auth topic's demo config injects the single HS256 secret with one @Value line, and that is the honest size for it. The moment two settings RELATE to each other (a deploy environment and the region it deploys into), they want a record, because the relationship is then expressed once, in the type. Two more gifts of the record form worth naming: the spring-boot-configuration-processor (a compile-time annotation processor you can add later) generates metadata so the IDE autocompletes your own keys in application.yml — impossible for scattered strings — and records make CONFIG TESTABLE as plain values: construct the record with any arguments in a unit test and assert the validation, no Spring context required. The testing topic's battery already exploits exactly that seam.""")),
                section("profiles", "Profiles: named variants of the configuration",
                        "A named bundle of settings — activated by SPRING_PROFILES_ACTIVE, expressed as multi-document yaml or @Profile beans — with the 'default' gotcha that bites everyone once.",
                        text("pf:model", """
                                Profiles are NAMED VARIANTS: `dev` means "the bundle of settings and beans a development environment wants", `prod` the production bundle. Activation is an environment fact — the env var SPRING_PROFILES_ACTIVE, or the property spring.profiles.active — which composes cleanly with everything above: compose sets `SPRING_PROFILES_ACTIVE=dev` for the dev stack (you saw the line in the compose quote), the prod compose sets `prod` the same way, and the same image boots as two different postures. Inside the app, a profile can select YAML (profile-specific documents or application-dev.yml files) and BEANS (@Profile("dev") on a definition — the wiring-level selection the spring-core topic covered; this topic stays on the config side).

                                The cleanest expression is the MULTI-DOCUMENT YAML this repo already uses: one file, documents separated by `---`, and `spring.config.activate.on-profile` deciding which document loads. The repo's own dev-only document mounts GraphiQL — the in-browser GraphQL IDE — exactly and ONLY in the dev profile:"""),
                        code("pf:dev-doc", "yaml",
                                "server/src/main/resources/application.yml — the dev-only document: GraphiQL mounts when (and only when) the dev profile is active",
                                "server/src/main/resources/application.yml", """
                                ---
                                spring:
                                  config:
                                    activate:
                                      on-profile: dev
                                  graphql:
                                    graphiql:
                                      enabled: true"""),
                        text("pf:default-gotcha", """
                                Now the gotcha that bites everyone exactly once, because it bit this repo too and a test pins it: the DEFAULT profile is not a profile. When no profile is active, Spring's `getActiveProfiles()` returns an EMPTY array, and the reserved name "default" applies — but it applies to DOCUMENT SELECTION, not to `@Profile("default")` matching, which will surprise you. Consequences worth memorizing: a document with `on-profile: dev` never loads on default (that is the GraphiQL deal — the graphql topic's test pins /graphiql 404ing outside dev, and the PROD stack inherits that posture by simply never setting the profile); an `@Profile("!dev")` bean exists on default (negation matches the no-profile case); and if you want to KNOW which posture you are in, print the active profiles, not spring.profiles.active the property — the inspector demo reports exactly this, mapping the empty case to the literal word "default" with a reading line that explains the difference.

                                Profile hygiene, briefly, because profiles rot fast: keep the SET small and orthogonal (dev/test/prod, maybe a local one for laptop-only overrides via application-local.yml, which you gitignore); never let a profile carry a BEHAVIOR you cannot name in one sentence ("the dev profile" is a posture, not a junk drawer); prefer DOCUMENTS over separate files when the variant is three lines (it stays visible in one place — exactly why the GraphiQL switch lives at the bottom of application.yml with its own comment block); and remember PROFILE GROUPS exist (spring.profiles.group.prod: [prod-db, prod-observability]) when a real deployment genuinely needs composable bundles. The rule of thumb the whole topic keeps re-deriving: profiles are for DIFFERENCES YOU CAN NAME AT BOOT; anything that changes AFTER boot is not a profile — it is a flag, and the flags section gives it its own machinery.""")),
                section("env-specific-behavior", "Environment-specific behavior beyond values",
                        "Some differences are not config values but behavior: cookie security, mounted tools, exposure surface. The rule: every difference is declarative, rides one of the established rails, and is visible in one place.",
                        text("eb:examples", """
                                The sections so far moved VALUES. But environments differ in BEHAVIOR too, and the temptation is to reach for `if (environment.equals("prod"))` — code that forks on strings, invisible in any config review. The disciplined alternatives all ride rails you already know. The env-var rail handles scalar behavior: the session cookie's `secure` flag must be false for plain-HTTP local dev and true the moment TLS terminates the connection — one property, environment-toggled, no code branch:"""),
                        code("eb:cookie", "yaml",
                                "server/src/main/resources/application.yml — TLS-aware cookies without a code branch: dev false, prod sets SESSION_COOKIE_SECURE=true",
                                "server/src/main/resources/application.yml", """
                                server:
                                  port: 8080
                                  # … the topic-23 comment block
                                  servlet:
                                    session:
                                      cookie:
                                        http-only: true
                                        same-site: lax
                                        secure: ${SESSION_COOKIE_SECURE:false}"""),
                        text("eb:mechanisms", """
                                The profile rail handles BUNDLED behavior: the GraphiQL document from the last section mounts an entire tool because a profile is active — no flag to forget, no string compare. The autoconfiguration rail handles CLASSPATH-determined behavior (the dependency-management topic's optional starters: devtools is `runtime`-scoped and typically excluded from the prod build, so the behavior difference ships as an artifact difference — the one place "different build" is legitimate, and it is decided by Maven profiles, not Spring's). And the EXCLUSION rail handles the rare case where an autoconfiguration must be switched off selectively — this repo's `spring.autoconfigure.exclude` line, which the security topic added for the gRPC resource-server autoconfig, is a behavior fork expressed as one yaml line with a paragraph of justification in the comment above it.

                                That comment is the real lesson: WHEN BEHAVIOR DIFFERS, THE DIFFERENCE GETS A NAME AND A HOME. Ask of every environment difference: which rail does it ride (env var for a scalar, profile for a bundle, exclusion for an autoconfig, flag for runtime)? If the answer is "none of them, I need an if-statement", the design is not done — the string comparison will outlive the reasoning that produced it, and the next reader (often you, at 3 a.m., in the incident from the debugging topic) will not find the branch that only runs in prod. One honest counterexample to close: SOME behavior legitimately cannot be declared — degrade-gracefully logic READS its environment at runtime (is PostgreSQL up RIGHT NOW) rather than at boot, and that is not config at all; it is the resilience machinery of the data topics, a different animal wearing a similar coat.""")),
                section("secrets-beyond-env", "Secrets: beyond .env",
                        "Env vars deliver secrets fine and STORE them badly: .env files, default literals, logs, and dump endpoints are where leaks live. Rotation, secret stores, and redaction as a structural rule.",
                        text("sk:env-files", """
                                The twelve-factor recommendation of env vars for config has a caveat everyone learns the hard way: env vars are a fine DELIVERY mechanism for secrets and a terrible STORAGE mechanism. The .env file on your disk is unencrypted, unrotated, usually backed up, and one `git add -A` away from becoming a permanent part of your repository's history — this repo's .gitignore carries `.env*` for exactly that reason, and the rule is: .env is a LOCAL CONVENIENCE for one developer's defaults, never shared, never committed, never mounted into prod. What production does instead, in ascending order of operational seriousness: the platform's secret store — Kubernetes Secrets (base64 wrappers, real security only with encryption-at-rest configured), Docker secrets (mounted files, readable only by the service), or a dedicated vault (HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager) — from which the orchestrator INJECTS the value into the process environment at deploy time, so the app still reads an env var and the storage/rotation story lives somewhere with audit logs and policies.

                                Spring meets the vault world at `spring.config.import` — a property-source line like `spring.config.import: vault://` pulls remote sources into the SAME relaxed-binding machinery (vault paths become properties), which is the elegant part: from the record's point of view a secret is just another property, and everything from the @ConfigurationProperties section (typing, validation, and the redaction discipline below) applies unchanged. The deployment topic wires the k8s side of this; what stays HERE is the app-side half, and this repo's real example is the JWT signing key:"""),
                        code("sk:jwt", "yaml",
                                "server/src/main/resources/application.yml — env over file over a LOUDLY-MARKED dev default: the SDG_JWT_SECRET discipline",
                                "server/src/main/resources/application.yml", """
                                  security:
                                    oauth2:
                                      resourceserver:
                                        jwt:
                                          secret-key: ${SDG_JWT_SECRET:dev-only-sdg-jwt-hs256-secret-3b9d6f01c2a8e45d7f0b}"""),
                        text("sk:redaction", """
                                Read that default the way the security topic intended: `dev-only-sdg-jwt-hs256-secret-…` is LOUD self-description — anything signed with it is demo material by construction, and a monitor can test `secretKey == the-known-dev-default` to answer "did prod forget the env var?" without ever learning a real key. That is the same verdict-only design the inspector demonstrates on guide.env.demo-api-key, and it generalizes into the THREE REDACTION RULES every surface in this repo obeys. ONE: a value classified SECRET has no code path into any answer — the inspector builds the redacted line from a constant, so no future edit can "accidentally" include the field. TWO: logs never carry secrets — which is why the request-logging filter logs method/path/status/duration and NOTHING else, and why you must resist logging whole request bodies "temporarily" (the observability topic's JSON structure exists so fields can be chosen, not sprayed). THREE: debug surfaces that can reveal secrets are OFF by default — the actuator `env` endpoint (which prints property VALUES, masked only for keys it recognizes) and `heapdump` (a heap dump CONTAINS every decrypted secret in memory — the debugging topic's warning applies with interest) are simply not in this app's exposure list, and if you enable them you own the authentication story that comes with them.

                                Rotation closes the loop: a secret with no rotation plan is a leak with a date. Symmetric keys like the JWT secret rotate by generating a new value, deploying it to the vault, and accepting that tokens signed with the old one die at their expiry — which is why token TTLs are short. The auth topic's refresh-rotation machinery exists for exactly that day. None of this is exotic; it is the boring discipline of treating secrets as SHAPE-PUBLIC, VALUE-NEVER — the same discipline the inspector panel renders live.""")),
                section("feature-flags", "Feature flags: the config that changes while running",
                        "Boot-time config and runtime flags are different animals. This app ships a real flag mechanism — the ScenarioStore behind GET/PATCH /api/demo/scenario — and its design teaches when flags earn their complexity.",
                        text("ff:model", """
                                Everything so far changed at BOOT: to move a value you restart the process. The last kind of environment difference refuses that constraint — it changes WHILE THE PROCESS RUNS: feature flags, kill switches, data-mode switches. Distinguish the three time-scales before reaching for any of them. BUILD-TIME (a dependency present or absent — devtools in the dev build) changes what the artifact IS. BOOT-TIME (env vars, profiles, the typed record) changes how the artifact STARTS. RUNTIME (flags) changes how the running process BEHAVES, without restart — and that power is why flags are the most expensive kind of config: a flag doubles the states your code can be in, and every flag you add and never remove is compound interest on complexity.

                                You do not need a flag platform to learn the discipline, because this app ships a REAL flag mechanism you have been using all along: the ScenarioStore. Its global state (data mode fake/live, failure injection on/off) and per-area overrides are runtime flags in production clothing — a Flip on PATCH, read on every request, initial value from the BOOT-time environment (SCENARIO_DATA_MODE bridges the rails: an env var seeds a runtime flag). The PATCH handler is the whole pattern — a partial update under a lock, nulls meaning "unchanged", answering the NEW state:"""),
                        code("ff:apply", "java",
                                "guide/core/scenario/ScenarioStore.java — the flag flip: partial update, synchronized, returns the new state",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                    public synchronized ScenarioState apply(ScenarioPatch patch) {
                                        Objects.requireNonNull(patch, "patch");
                                        ScenarioState current = state;
                                        ScenarioState next = new ScenarioState(
                                                patch.dataMode() != null ? patch.dataMode() : current.dataMode(),
                                                patch.injectFailure() != null ? patch.injectFailure() : current.injectFailure());
                                        state = next;
                                        return next;
                                    }"""),
                        text("ff:hygiene", """
                                The inspector demo proves the mechanism end-to-end: its "flip data-mode" button PATCHes /api/demo/scenario and re-inspects, and the flag line changes while every guide.env.* value stays frozen — boot-shaped versus runtime-shaped, one click apart. When flags graduate beyond a teaching app, the same design questions come with them. WHERE does the flag live — in-process state (ScenarioStore: fast, per-instance, lost on restart, and each replica flips INDEPENDENTLY, which for a demo is the point and for a real feature rollout is a split-brain you must want), a shared store (redis-backed: consistent across replicas, still your code), or a flag PLATFORM (LaunchDarkly, OpenFeature with a backend, Spring Cloud Config's @RefreshScope): targeting rules, percentage rollouts, audit trails — everything the demo deliberately does not have. HOW does code read it — through an injected collaborator (this repo's use cases take the ScenarioStore via the composition root, never a static), so a flag check is testable by flipping the store, exactly what the testing topic's failure-injection exhibit does. And WHEN does the flag DIE — the iron discipline: every feature flag is BORN with an expiry (the merge-to-done date, the rollout-complete checkpoint), because a two-year-old flag is not a feature switch, it is an environment difference that hides in code — the exact disease this topic started with, now wearing a toggled costume. Flags are a tool for CHANGE, not a substitute for decisions.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic, curled: inspect the running process's environment story, flip a flag and re-inspect, then ask the platform itself what it will inject — plus the one caution about the env-dump endpoint.",
                        text("tc:intro", """
                                Every screen in this topic has a terminal twin. The tour assumes the server on localhost:8080 (in compose, the same endpoints answer inside the compose network). The inspector is one thin GET — the same controller shape as every demo in the guide:"""),
                        code("tc:controller", "java",
                                "guide/presentation/demo/EnvDemoController.java — one endpoint, one use case, already the wire shape",
                                "server/src/main/java/guide/presentation/demo/EnvDemoController.java", """
                                @GetMapping(value = "/api/demo/env/inspector", produces = MediaType.APPLICATION_JSON_VALUE)
                                EnvInspection inspect() {
                                    return inspectEnvironment.execute();
                                }"""),
                        text("tc:tour", """
                                Ask the process about its environment, flip the runtime flag the demo flips, and watch the answer change while the typed config does not:"""),
                        code("tc:curl", "bash",
                                "the environment story from a terminal",
                                null, """
                                curl -s localhost:8080/api/demo/env/inspector | python3 -m json.tool | head -30
                                # {
                                #   "activeProfiles": ["default"],
                                #   "properties": [
                                #     {"key": "guide.env.deploy-environment", "value": "local", "classification": "safe"},
                                #     {"key": "guide.env.region", "value": "local", "classification": "safe"},
                                #     {"key": "guide.env.maintenance-mode", "value": "false", "classification": "safe"},
                                #     {"key": "guide.env.demo-api-key", "value": "<redacted>", "classification": "secret"}],
                                #   "demoSecretIsDevDefault": true,
                                #   "flags": [
                                #     {"name": "scenario.data-mode", "state": "fake", "runtimeSwitchable": true, ...},
                                #     {"name": "scenario.inject-failure", "state": "false", "runtimeSwitchable": true, ...}],
                                #   "reading": ["profiles: default — 'default' means NO profile is active; ...", ...]
                                # }

                                # flip the runtime flag, then re-inspect: the flag moves, the config does not
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'content-type: application/json' -d '{"dataMode":"live"}'
                                curl -s localhost:8080/api/demo/env/inspector | grep -A2 'data-mode'

                                # inside compose the SAME build answers differently — env vars at work:
                                docker compose -f .docker/docker-compose.yml exec server printenv | grep -E 'SPRING_|GUIDE_'
                                # SPRING_PROFILES_ACTIVE=dev
                                # GUIDE_DEPLOY_ENV=dev
                                # SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/guide

                                # ask compose what the container WILL receive, without running it:
                                docker compose -f .docker/docker-compose.yml config | grep -A9 'environment:'

                                # the caution tour: the env-dump endpoint exists but stays unexposed here.
                                # If you enable it, it prints property VALUES (masking only known keys):
                                curl -s localhost:8080/actuator/env | head -c 120
                                # {"error":{"code":"not_found",...}} — the deliberate posture: not mounted"""),
                        text("tc:close", """
                                That last line is the topic in miniature: the machinery to DUMP the entire environment is one yaml line away, and this app's answer is a deliberate no — environment facts travel on typed, curated, redacted rails (the inspector), not as a firehose. You now hold the whole stack of rails in order — env vars with readable defaults at the bottom, one typed record validating them at boot, profiles bundling the boot-time variants, env-toggled behavior for the scalars, vaults and redaction discipline for the secrets, and runtime flags for everything that must change while the process lives. The deployment topic takes the artifact this machinery produces and puts it on the road: images, compose, and the Kubernetes that injects these very environment variables at scale.""")));
    }
}

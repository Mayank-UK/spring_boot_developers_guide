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
 * Topic 37 — Deployment. Group platform, order 8. The environments topic
 * built the machine that reads configuration; this topic builds the road the
 * artifact travels: multi-stage layered-jar Docker images (the repo's own
 * .docker/ files), compose as the stack-in-one-file (inspected live by the
 * deploy:stack demo — a typed mirror of docker-compose.yml plus the real
 * graceful-shutdown posture), the reverse proxy and TLS termination
 * boundary, graceful shutdown choreographed end-to-end, CI/CD as the
 * promotion path, parity and secrets at deploy time, and the real
 * deploy/k8s/ manifests this topic ships (namespace, Deployment with the
 * actuator probe pair, Service, ConfigMap, Ingress, HPA) — closing with
 * rollouts, infrastructure-as-code, and the Linux survival kit.
 */
public final class DeploymentTopic {

    private DeploymentTopic() {
    }

    public static Topic topic() {
        return new Topic("deployment", "Deployment",
                "Shipping the artifact and running the world around it: multi-stage layered-jar "
                        + "images, compose stacks, reverse proxies and TLS, graceful shutdown, CI/CD "
                        + "promotion, parity and secrets — then the real deploy/k8s/ manifests this "
                        + "repo carries (probes, rollouts, HPA), infrastructure as code, and the "
                        + "Linux survival kit for the day you are the one in the shell.",
                TopicGroupId.PLATFORM, 8,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("ship-vs-run", "Ship vs run: two different verbs",
                        "Developing is running a process; deploying is shipping an artifact plus the instructions for running it. Confusing the two is how 'just SSH in and restart it' happens.",
                        text("sv:two-verbs", """
                                Everything before this topic was about RUNNING code: a process starts, reads its configuration (the environments topic's machinery), serves requests, and stops. Deployment is the other verb — SHIPPING — and it has a different shape: you produce an ARTIFACT once (a jar, an image), you make it IMMUTABLE (the same bytes everywhere, the environments topic's first law), and you hand it to machinery that knows how to start it, watch it, replace it, and stop it. The distinction sounds academic until the first 2 a.m. incident, when "how do I run this?" and "what exactly is running?" are suddenly the same question, and the answer is only good if the artifact was built to be answerable.

                                The artifact this repo ships is a Docker IMAGE, and the file that defines it teaches the whole shape in fifteen lines. Two stages: a BUILDER that has the JDK, Maven, and the source long enough to produce the layered jar, and a RUNTIME that has neither the source nor the build tools — only a JRE and the extracted application. What runs in production is the second stage; the first stage is scaffolding that never ships:"""),
                        code("sv:stages", "dockerfile",
                                ".docker/Dockerfile.server.prod — two FROMs: a builder with the toolchain, a runtime with only what the app needs",
                                ".docker/Dockerfile.server.prod", """
                                FROM maven:3.9-eclipse-temurin-25 AS build
                                WORKDIR /srv
                                # … build: package + extract the layered jar (topic 31 quotes those lines)
                                FROM eclipse-temurin:25-jre-noble
                                WORKDIR /app
                                # … four layer COPYs, least-changed first
                                EXPOSE 8080
                                ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]"""),
                        text("sv:contract", """
                                Read the second stage as a CONTRACT between you and the runtime: I will give you a process that opens port 8080, started by the Boot launcher, with no build tools, no source, no shell conveniences you did not ask for. Every orchestration system in the rest of this topic — compose, Kubernetes — is machinery for holding containers to that contract at scale: start N of them, check the port, route traffic, replace them when the image changes. The ENTRYPOINT line is worth one more look: the launcher is spring-boot-loader, and the thing it launches is the four-directory layout the dependency-management topic dissected — the layered jar. Images inherit the same lesson: tags are names, DIGESTS are identities, and a deployment that says sbdg_server_prod:latest is a deployment that cannot answer "what exactly is running?" — pin or regret.

                                One honest sizing note before the mechanics: most Spring Boot apps never need Kubernetes. A single host with docker compose — or a platform like Cloud Run, App Service, or Fly.io that takes an image and gives you a URL — runs the same layered-jar image with TLS, logs, and restarts as a managed service. The k8s sections later in this topic are real and worth learning, but the decision ladder is: one container on a platform first, compose when you own the host and need its services, Kubernetes when you have many teams, many services, or compliance that demands the machinery. Everything before the k8s sections applies to ALL of those rungs.""")),
                section("docker-multi-stage", "Docker: multi-stage builds and the layered jar",
                        "Build tools do not ship. Multi-stage builds keep the toolchain in a stage that dies, and Spring Boot's layered jar orders the surviving bytes by change frequency.",
                        text("dk:idea", """
                                A Docker image is a stack of filesystem layers plus a command to run. The naive Dockerfile — FROM maven, COPY source, RUN mvn package, CMD java -jar — has two diseases. First, SIZE and SECURITY: the image that ships to production carries the entire JDK, Maven, your source tree, and every intermediate artifact; nobody needs a compiler in production, and everything present is attack surface (the security topic's least-privilege instinct, applied to filesystems). Second, CACHE ECONOMICS: every source change invalidates every layer after the COPY, so each rebuild re-does the dependency download — the dependency-management topic covered that half in detail.

                                MULTI-STAGE is the cure for the first disease: a build with a beginning and an end, where the end is a COPY --from that harvests only the output. The client image is the cleanest example in this repo — Node builds the SPA, nginx serves it, and the Node stage (with node_modules' hundred-megabyte tree) never ships:"""),
                        code("dk:client", "dockerfile",
                                ".docker/Dockerfile.client.prod — the same shape twice over: build in Node, ship nginx + static files only",
                                ".docker/Dockerfile.client.prod", """
                                FROM node:22-bookworm AS build
                                WORKDIR /app
                                # Lockfile-first install keeps the dependency layer cacheable.
                                COPY client/package.json client/package-lock.json ./
                                RUN npm ci
                                # … then source, then the Vite build
                                FROM nginx:1.27-alpine
                                COPY .docker/nginx.conf /etc/nginx/conf.d/default.conf
                                COPY --from=build /app/dist /usr/share/nginx/html
                                EXPOSE 80"""),
                        text("dk:details", """
                                Look at what the lockfile-first COPY buys: package.json and package-lock.json change only when dependencies change, so the npm ci layer is a cache hit across ordinary commits — the same least-changed-first economics as the server's layered jar, expressed as instruction ORDER in a Dockerfile. Three more details that earn their keep in every real pipeline. The .dockerignore at the repo root keeps node_modules, target, .git, and .env files OUT of the build context — smaller uploads, faster builds, and (critically) no secret files baked into image layers by accident; a layer that ever contained a secret is a secret you must treat as published, because layers are forever. The runtime image tag matters too: this repo runs eclipse-temurin:25-jre-noble — the JRE, not the full JDK, on a slim Ubuntu base; smaller, fewer packages, fewer CVEs to track. And the JVM flag worth knowing for containers: the JVM is container-aware by default on modern JDKs (it sees the cgroup memory limit and sizes the heap accordingly) — but it sizes to a fraction of the LIMIT, so set requests and limits deliberately (the k8s section does) rather than letting defaults guess.

                                Finally, the extraction step from the server image — java -Djarmode=tools -jar target/*.jar extract --layers — is what converts the fat jar into the four directories the ENTRYPOINT launcher expects. It is a PACKAGING decision, invisible at runtime: the JVM sees the same classpath it always did. The build tools, the source, and the extraction step itself all stay in the dead first stage; production receives bytes, ordered so that the parts that never change ride layers that never rebuild.""")),
                section("compose-stack", "Compose: the whole stack in one file",
                        "Six services, one versioned file, service discovery by name, healthcheck-gated boot order — inspected live by the deploy:stack demo.",
                        text("cs:model", """
                                Docker runs containers; COMPOSE runs systems. A compose file is the declaration of a whole world: which containers exist, what images they run, which ports they publish, what environment they carry, what volumes they own, and — the part nothing else gives you this cheaply — how they find each other. Every service in a compose network gets a DNS name equal to its service name: the server connects to postgres:5432 not because anyone configured DNS, but because compose's network promises it. That single mechanism is why the environments topic's rails work: the SAME environment variables that point at localhost on a laptop point at service names in compose, and the application.yml default-vs-override pattern needs no changes at all.

                                The dev stack's server service is the honest specimen — note what it runs: not the prod image, but Maven itself, against the bind-mounted source, so devtools hot reload works:"""),
                        code("cs:server", "yaml",
                                ".docker/docker-compose.yml — the dev server service: Maven on live source, published port, healthcheck-gated dependencies",
                                ".docker/docker-compose.yml", """
                                server:
                                  image: maven:3.9-eclipse-temurin-25
                                  container_name: springboot_developers_guide_server_dev
                                  restart: unless-stopped
                                  working_dir: /srv
                                  command: mvn -q spring-boot:run
                                  ports:
                                    - 8080:8080
                                  # … environment (the topic-36 rails) + volumes + the m2 cache
                                  depends_on:
                                    postgres:
                                      condition: service_healthy
                                    redis:
                                      condition: service_healthy
                                    mongo:
                                      condition: service_healthy"""),
                        text("cs:healthcheck", """
                                The depends_on block with condition: service_healthy is the detail most stacks get wrong: without it, depends_on only orders CONTAINER STARTS, and postgres "starts" several hundred milliseconds before it accepts connections — the classic race where the app boots before the database and crashes on first contact. The healthcheck (pg_isready for postgres, redis-cli ping, mongosh ping in this file) moves the gate from "process exists" to "service ANSWERS", which is the only condition that matters. Kubernetes replaces this with init containers or readiness gating — the same lesson, heavier machinery.

                                The whole stack, then: dev (tooling, runs no app), server (Maven on live source), client (Vite, proxying /api to the server service), postgres (the only SQL engine), redis and mongo (optional services — the app degrades gracefully without them, which is WHY they can be last in the boot order without risk). The demo below serves exactly that inventory, from a typed record that mirrors the compose file — the demo cannot parse yaml at runtime (the running jar has no repo checkout), so the mirror documents the stack the way the file spells it, and the pair changes together or the snippet checks notice:"""),
                        code("cs:mirror", "java",
                                "guide/domain/deployment/ComposeStack.java — the typed mirror of the compose stack the demo serves",
                                "server/src/main/java/guide/domain/deployment/ComposeStack.java", """
                                public static List<DeploymentInspection.ServiceLine> devServices() {
                                    return List.of(
                                            new DeploymentInspection.ServiceLine("dev", "sbdg_dev_toolchain", List.of(),
                                                    "the devcontainer tooling container — JDK 25 + Maven + Node 22; runs no app"),
                                            new DeploymentInspection.ServiceLine("server", "maven:3.9-eclipse-temurin-25",
                                                    List.of("8080:8080"),
                                                    "Spring Boot dev server — mvn spring-boot:run on the bind-mounted source, devtools hot reload"),"""),
                        demo("cs:demo", "deploy:stack", "Inspect the deployment story, live",
                                "The stack answers for itself: what ships (the prod container — image, base, launcher, port), the six-service compose world with each service's role, the twelve-factor env rails with dev and prod values side by side (parity rails rendered identically, deliberate differences highlighted), the actuator probe pair the orchestrator polls, and the graceful-shutdown posture read from the real property sources — one deterministic GET."),
                        text("cs:design", """
                                The use case behind the demo is deliberately boring — that is the lesson. It reads the typed mirror and the shutdown posture and builds the wire record; the one Spring-aware act (reading server.shutdown from the Environment) happens ONCE at the composition root, exactly like the environments topic's ActiveProfiles pattern:"""),
                        code("cs:usecase", "java",
                                "guide/domain/deployment/InspectDeployment.java — assemble the story: container, stack, rails, probes, the live shutdown posture",
                                "server/src/main/java/guide/domain/deployment/InspectDeployment.java", """
                                public DeploymentInspection execute() {
                                    return new DeploymentInspection(
                                            new DeploymentInspection.ContainerLine("sbdg_server_prod", "eclipse-temurin:25-jre-noble",
                                                    "org.springframework.boot.loader.launch.JarLauncher", 8080),
                                            ComposeStack.devServices(),
                                            ComposeStack.envRails(),
                                            PROBES,
                                            new DeploymentInspection.ShutdownLine(shutdownPosture.mode(), shutdownPosture.timeout(),
                                                    DRAIN_CONTRACT),
                                            reading());
                                }""")),
                section("reverse-proxy-tls", "The reverse proxy and TLS",
                        "One process owns the edge: TLS termination, static serving, API proxying. The app behind it stays TLS-agnostic except for the one cookie flag that must know.",
                        text("rp:why", """
                                The single-container app that owns its own TLS is a rarity; the standard shape is a REVERSE PROXY at the edge — nginx, Traefik, Caddy, a cloud load balancer, or Kubernetes' ingress controller — owning three jobs the app should not do. TLS TERMINATION: certificates renew, ciphers rotate, HTTP/2 and HTTP/3 get negotiated at the edge, and none of it redeploys the app; the connection into the container is plain HTTP on a private network. STATIC + SPA SERVING: the client's built assets are files, and nginx serves files better than any JVM ever will. ROUTING: one edge, many backends — /api to the Spring server, /docs too, everything else to the static SPA. This repo's entire proxy config is one small file:"""),
                        code("rp:nginx", "nginx",
                                ".docker/nginx.conf — the whole edge: proxy /api to the compose server service, SPA history fallback for everything else",
                                ".docker/nginx.conf", """
                                # Proxy API calls to the Spring Boot server (compose service name).
                                location /api/ {
                                    proxy_pass http://server:8080;
                                    proxy_http_version 1.1;
                                    proxy_set_header Upgrade $http_upgrade;
                                    proxy_set_header Connection 'upgrade';
                                    proxy_set_header Host $host;
                                    proxy_cache_bypass $http_upgrade;
                                }

                                # SPA history fallback: unknown paths serve the app, which renders the 404 view.
                                location / {
                                    try_files $uri $uri/ /index.html;
                                }"""),
                        text("rp:details", """
                                Two lines in that file carry more than they show. The Upgrade/Connection header pair is WebSocket support: the realtime topic's chat demo upgrades HTTP to WebSocket, and proxies that do not forward the Upgrade header break it silently — the pair is the entire cost of proxying WS. try_files with the /index.html fallback is SPA routing: the client is a history-mode SPA whose URLs (/topics/deployment, /demos) exist in the router, not on disk, so unknown paths must serve the app and let IT render the 404 — the proxy's 404 would strand users outside the application shell.

                                What the proxy means for the app is mostly ONE property: the environments topic's SESSION_COOKIE_SECURE rail. Behind TLS termination the browser sees https, but the app sees plain HTTP — if the app set the cookie's secure flag from its own connection, it would never set it. So the flag rides an environment variable the edge-owning deployment sets to true; the app stays TLS-agnostic. The two headers a Spring app behind a proxy usually also wants are X-Forwarded-For (the real client IP — the proxy's IP is useless in logs) and X-Forwarded-Proto (lets Boot's forward-headers strategy rewrite absolute URLs and redirect decisions as https). The k8s ingress later in this topic does the same edge job with an Ingress object and cert-manager instead of a config file and certbot — same responsibilities, platform machinery. Which raises the real design question: TLS at the EDGE (one certificate, one renewal, internal traffic plain) versus TLS END-TO-END (mTLS between services, the service-mesh world). Edge TLS is the honest default; end-to-end earns its operational cost when traffic crosses trust boundaries you do not own.""")),
                section("graceful-shutdown", "Graceful shutdown",
                        "SIGTERM is a request, not a switch: stop accepting connections, finish what is in flight, then go. Two yaml lines in the app, one grace period and a preStop sleep in the orchestrator.",
                        text("gs:problem", """
                                Every deployment mechanism in this topic — compose up, k8s rollout, a platform redeploy — eventually sends your process SIGTERM and expects it to die within a grace period (compose waits 10 seconds by default; Kubernetes 30). Boot's DEFAULT behavior on SIGTERM is an immediate shutdown: the web server stops AT ONCE, and every request mid-flight dies — truncated JSON bodies for users, connection resets and 502s for callers mid-retry, transactions half-committed if your @Transactional boundaries are unlucky. At deploy time this happens to EVERY pod replaced by a rollout, so "we deploy at night because deploys drop requests" is purely a shutdown-posture bug wearing an ops schedule.

                                GRACEFUL shutdown inverts the order: on SIGTERM, the web server first stops accepting NEW connections, then lets in-flight requests COMPLETE, and only then closes the context — with a per-phase budget so a hung request cannot pin the process open forever. The whole posture is two lines of yaml:"""),
                        code("gs:lifecycle", "yaml",
                                "server/src/main/resources/application.yml — the per-phase drain budget (inside the spring: block)",
                                "server/src/main/resources/application.yml", """
                                lifecycle:
                                  timeout-per-shutdown-phase: 30s"""),
                        code("gs:shutdown", "yaml",
                                "server/src/main/resources/application.yml — the switch itself (inside the server: block)",
                                "server/src/main/resources/application.yml", """
                                server:
                                  port: 8080
                                  # … the topic-23 cookie block, then the topic-37 rationale
                                  shutdown: graceful"""),
                        text("gs:choreography", """
                                In an orchestrator the app's two lines are necessary but not sufficient, because TWO processes are involved: the app draining, and the platform's LOAD BALANCER still routing new traffic to the pod for the seconds it takes everyone to notice it is going away. The k8s choreography, quoted from the real Deployment manifest, is: kubelet sends SIGTERM and starts a 45-second clock; the preStop hook sleeps 5 seconds — long enough for the endpoint removal to propagate so no NEW request arrives while the app is already draining; Boot then finishes in-flight work inside its 30-second budget; the pod exits before the 45-second clock, and nobody had to be killed:"""),
                        code("gs:grace-period", "yaml",
                                "deploy/k8s/deployment.yml — the platform's clock: deliberately LONGER than the app's 30s drain budget",
                                "deploy/k8s/deployment.yml", """
                                spec:
                                  terminationGracePeriodSeconds: 45"""),
                        code("gs:prestop", "yaml",
                                "deploy/k8s/deployment.yml — the drain dance's pause: cover endpoint-propagation before Boot starts draining",
                                "deploy/k8s/deployment.yml", """
                                lifecycle:
                                  preStop:
                                    exec:
                                      command: ["sh", "-c", "sleep 5"]"""),
                        text("gs:order", """
                                The ORDERING is the contract: the platform's grace period (45s) must EXCEED the app's drain budget (30s) plus the preStop sleep, or the kubelet SIGKILLs the container mid-drain and you are back to immediate shutdown with extra steps. The same contract exists in compose (stop_grace_period), behind every load balancer (deregistration delay), and in every platform's "draining" state — learn it once here, recognize it everywhere. Two app-side companions complete the picture: @PreDestroy and SmartLifecycle stop callbacks run during the context close, INSIDE the same phase budget — close connection pools, flush buffers, finish scheduled work politely; and the background-jobs topic's lesson applies with interest at shutdown: a job mid-retry should let its retry state persist and be picked up after restart, not block the drain. The demo above reports this posture live — mode and timeout read from the real property sources, which is how you verify a pod actually carries them instead of trusting the yaml you remember.""")),
                section("ci-cd", "CI/CD: the promotion path",
                        "CI is the gate that runs the suite on every candidate change; CD is the machinery that moves the artifact between environments. This guide runs no hosted CI by choice — the gate is the same suite, green on a clean local machine.",
                        text("cc:ci", """
                                The testing topic built the suite; CI is where the suite stops being a courtesy and becomes the GATE. The contract is boring and load-bearing: every candidate change — every pull request, every push to the mainline — runs the full server and client suites, on a clean machine, against real infrastructure (the server suite's Testcontainers postgres), and a red build cannot become a deployable artifact. A hosted pipeline adds three things to that contract, none of them a different test: a CLEAN RUNNER nobody pre-warmed (so "works on my machine" cannot pass), enforcement AT THE MERGE BOUNDARY (the branch does not merge while red — superseded runs cancel each other, and every job carries an explicit wall-clock timeout), and CACHE HYGIENE (Maven and npm caches keyed on the pom and lockfile hashes, so a dependency bump costs one cold run, not one per commit). This guide deliberately ships none of that machinery: a single-maintainer teaching repo has no PR flood to gate, and the definition of done stays runnable by every reader — the devcontainer and the two docker one-liners ARE the gate, the same two stages a pipeline would run. Anywhere this guide says "the suite is green," a hosted setup would say it with a badge; the meaning is identical, and wiring the same commands into GitHub Actions or GitLab CI is an afternoon, not a migration — that is the point of keeping the contract command-shaped.
                                """),
                        text("cc:cd", """
                                CD — continuous delivery/deployment — starts where CI ends: the same immutable artifact, moved along environments by machinery, never rebuilt per stop. The promotion ladder mirrors the environments topic: the pipeline builds the IMAGE once (mvn package inside the multi-stage Docker build, which is why the Dockerfile skips tests — the suite already ran), tags it with the commit SHA (an immutable name — latest is not a version, it is a lie), pushes it to a registry, and each environment's deploy step only RE-POINTS at the new digest. Staging auto-deploys from main; production deploys on a tag or a promoted release — the human gate is a policy decision, and both policies are legitimate; what is never legitimate is rebuilding the artifact per environment (a different build is a different program — topic 36's first law, now with a pipeline around it).

                                The registry completes the immutability story the dependency-management topic told for jars: a pushed image digest is bytes with a name, never overwritten; ROLLBACK is re-pointing at the previous digest, minutes not archaeology. That is also why the k8s manifests later in this topic pin an image tag and treat kubectl rollout undo as a first-class verb — the undo works precisely because old artifacts still exist. The failure modes of CD are organizational more than technical: deploys that require a human runbook (toil that rots), pipeline secrets wider than they need to be (the registry token that can push can also overwrite tags — scope it), and the big-bang release cadence that makes every deploy scary. The cure for all three is the same: deploy smaller, deploy more often, let the suite and the probes do the deciding — which is exactly what the rollouts section machinery exists for.""")),
                section("parity-and-secrets", "Parity and secrets at deploy time",
                        "Differences between environments must be named and deliberate — and secrets are the difference that must never be named in git. The k8s ConfigMap/Secret split makes both rules mechanical.",
                        text("ps:parity", """
                                The environments topic stated the law (the build is identical; the environment supplies the difference); deployment is where the law gets audited. PARITY means: of all the things that differ between dev and prod, every single one is a VALUE on a rail you can list, and — the stronger form — everything that CAN be identical IS identical. The demo above renders this: the datasource endpoints, mongo, and redis rails carry IDENTICAL values in both compose stacks (the service names resolve inside each network the same way), while the deliberate differences (SPRING_PROFILES_ACTIVE, GUIDE_DEPLOY_ENV, and prod's compose-REQUIRED secret rails — SDG_JWT_SECRET, and SDG_DB_PASSWORD feeding BOTH the app's datasource password and the postgres service's POSTGRES_PASSWORD, so the database and the app cannot disagree) are exactly the values that NAME the environment. When someone proposes "just for prod" infrastructure — an extra container, a different image, a patched dependency — that is a parity break, and the answer is the same as the environments topic's: give it a name and a home, or do not ship it.

                                Boot ORDER is parity's quiet half. The prod compose stack gates the server on all three data services being healthy — same block, same conditions as dev (quoted in the compose section) — because a server that starts before its dependencies is a different program at 3 a.m. than the one you tested at noon:"""),
                        code("ps:prod-deps", "yaml",
                                ".docker/docker-compose.prod.yml — the prod stack gates boot on the same service_healthy conditions dev does",
                                ".docker/docker-compose.prod.yml", """
                                depends_on:
                                  postgres:
                                    condition: service_healthy
                                  redis:
                                    condition: service_healthy
                                  mongo:
                                    condition: service_healthy"""),
                        text("ps:secrets", """
                                SECRETS at deploy time are the parity rule's evil twin: the thing that must differ per environment must also never live in git, never live in an image layer, never ride a plaintext object. The k8s answer is a type split that makes the discipline mechanical — ConfigMap for NON-secret config (plaintext, reviewable, this repo ships one) and Secret for credentials (base64 — which is ENCODING, not encryption, so the real protections are RBAC on who may read it, encryption-at-rest on the cluster, and external secret stores for the serious cases: External Secrets Operator or Vault — they keep the values outside the cluster entirely and sync them in). The app never knows which kind it read: a Secret mounts as an environment variable exactly like a ConfigMap entry, so application.yml's env-default rails work unchanged — the environments topic's SDG_JWT_SECRET default would arrive from a Secret's key, and the app could not tell, which is the point. What the app MUST know is never to say: the redaction rules from topic 36 apply to deployment surfaces too — no secret in a log line, no secret in an error message, no secret in an actuator endpoint, and the loudly-fake dev default stays detectable so "prod forgot the secret" is a glance, not a breach audit.

                                The deploy/k8s ConfigMap this topic ships is deliberately all non-secret — the guide's env rails and service endpoints — with the Secret half left as the pattern to add when a real credential exists. That restraint is itself the lesson: a manifest set that COULD hold secrets and does not is reviewable at a glance; one where credentials and config intermix trains reviewers to skim past both.""")),
                section("kubernetes", "Kubernetes: the real manifests",
                        "This repo ships deploy/k8s/ — namespace, ConfigMap, Deployment with the actuator probe pair, Service, Ingress, HPA. Every line below is quoted from those files.",
                        text("k8:model", """
                                Kubernetes is a control loop over declared state: you write WHAT should exist (N replicas of this image, polled on these paths, routable at this name), the cluster reconciles actual state toward it — starting pods when they die, scaling when the HPA says so, rolling when the image changes. The unit of deployment is the DEPLOYMENT, which manages ReplicaSets, which manage PODS — a hierarchy that exists so that "replace every pod with the new image, without dropping traffic" is a primitive instead of a script. This topic ships a real manifest set under deploy/k8s/, one concern per file; everything the rest of this section and the next quotes comes from those files, verified like every snippet in this guide.

                                The fence first — a Namespace, so the whole stack is listable, quota-able, and deletable as a unit:"""),
                        code("k8:namespace", "yaml",
                                "deploy/k8s/namespace.yml — the stack's fence: every object below names it",
                                "deploy/k8s/namespace.yml", """
                                apiVersion: v1
                                kind: Namespace
                                metadata:
                                  name: sbdg-guide
                                  labels:
                                    app.kubernetes.io/name: springboot-developers-guide
                                    app.kubernetes.io/part-of: springboot-developers-guide"""),
                        text("k8:config", """
                                Then config, riding the SAME twelve-factor rails as compose — the ConfigMap from the parity section, mounted wholesale with envFrom so every key becomes an environment variable. Note the values are members of AppConfig's closed set (a GUIDE_DEPLOY_ENV typo fails the pod's boot — fail-fast crossing the container boundary) and the datasource rails point at in-cluster DNS names instead of compose service names: same rail, different resolution:"""),
                        code("k8:configmap", "yaml",
                                "deploy/k8s/configmap.yml — non-secret config: the env rails the app already knows how to read",
                                "deploy/k8s/configmap.yml", """
                                data:
                                  GUIDE_DEPLOY_ENV: staging
                                  GUIDE_REGION: cluster-local
                                  # … the datasource/redis/mongo rails by in-cluster DNS name
                                  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres.sbdg-guide.svc.cluster.local:5432/guide"""),
                        text("k8:probes", """
                                The Deployment wires the image to the platform's two questions. The actuator side already exists — the observability topic enabled the probe pair, and Boot serves kubernetes-shaped paths even off-cluster:"""),
                        code("k8:probes-yml", "yaml",
                                "server/src/main/resources/application.yml — the actuator side of the probes: k8s-style liveness/readiness paths, on",
                                "server/src/main/resources/application.yml", """
                                endpoint:
                                  health:
                                    probes:
                                      enabled: true"""),
                        code("k8:probes-manifest", "yaml",
                                "deploy/k8s/deployment.yml — the platform side: envFrom the ConfigMap, then the two probes with their contracts",
                                "deploy/k8s/deployment.yml", """
                                envFrom:
                                  - configMapRef:
                                      name: server-config
                                livenessProbe:
                                  httpGet:
                                    path: /actuator/health/liveness
                                    port: http
                                  periodSeconds: 10
                                  failureThreshold: 3
                                readinessProbe:
                                  httpGet:
                                    path: /actuator/health/readiness
                                    port: http
                                  periodSeconds: 5
                                  failureThreshold: 2"""),
                        text("k8:probe-semantics", """
                                The two probes answer different questions and the difference is the most operationally load-bearing distinction in this topic. LIVENESS asks "is the process beyond saving?" — a failing liveness probe gets the pod RESTARTED, so it must test only what a restart fixes (deadlock, leaked memory) and must NOT test dependencies, or a database outage becomes a restart storm that finishes off the database (this is why application.yml turns the db/redis/mongo health indicators OFF — an optional service is not a liveness condition, the observability topic's verdict). READINESS asks "should traffic route here?" — a failing readiness probe removes the pod from the Service's endpoints WITHOUT restarting it: still running, just not receiving. Boot's probe endpoints encode exactly that split (liveness maps to the livenessState health group; readiness to readinessState, which reports REFUSING while the app is still starting). Swap them and every deploy restarts the fleet; forget the distinction and you will, once.

                                The Service and Ingress complete the path in the infrastructure-as-code section below; the HPA rides with the rollouts. What makes the manifest set REAL rather than illustrative: it is applied to the same image compose builds, read by the same snippet checks as every other file in this guide, and parse-verified by a suite test — the same source-of-truth discipline the whole guide runs on.""")),
                section("rollouts-and-scaling", "Rollouts and scaling",
                        "Zero-downtime replacement as four lines of strategy, rollbacks as a first-class verb, and the HPA turning observed load into replica count.",
                        text("ro:strategy", """
                                A deploy on Kubernetes is a ROLLOUT: new pods come up beside the old, readiness decides when each new pod may serve, the old drain gracefully (the shutdown choreography from earlier), and the Service's endpoint list quietly shifts. None of it is a script — it is four lines of declared strategy:"""),
                        code("ro:strategy-yml", "yaml",
                                "deploy/k8s/deployment.yml — the rollout contract: never below desired, surge one extra, readiness gates routing",
                                "deploy/k8s/deployment.yml", """
                                replicas: 2
                                strategy:
                                  type: RollingUpdate
                                  rollingUpdate:
                                    maxUnavailable: 0
                                    maxSurge: 1"""),
                        text("ro:mechanics", """
                                Read those two numbers as the deploy's traffic contract: maxUnavailable: 0 promises capacity never DROPS below desired during the rollout (old pods only die as new ones turn ready), and maxSurge: 1 allows one extra pod of headroom to make that possible on a full cluster — the pair is zero-downtime in declarative form. The readiness gate is what makes it true: the surge pod receives no traffic until /actuator/health/readiness says yes, and a pod that never turns ready never enters rotation at all (the rollout stalls loudly instead of shipping a broken version halfway — stall, notice, kubectl rollout undo; the rollback works because the registry still holds the previous digest, the CI/CD section's immutability dividend). Slow JVM starts deserve one flag: a startupProbe (not in this manifest set — the app boots fast enough) separates "still starting" from "dead", giving slow warmups a grace window before liveness starts counting; add one the day rollouts flap on cold start.

                                SCALING is the same declarative style: the HPA watches a metric and reconciles replicas toward a target — horizontal scaling, which is the only kind a stateless app needs (the scaling topic takes the statelessness side; this one shows the machinery):"""),
                        code("ro:hpa", "yaml",
                                "deploy/k8s/hpa.yml — scale on observed CPU: 2–6 replicas at a 70% target, with a scale-down cool-off",
                                "deploy/k8s/hpa.yml", """
                                scaleTargetRef:
                                  apiVersion: apps/v1
                                  kind: Deployment
                                  name: server
                                minReplicas: 2
                                maxReplicas: 6
                                metrics:
                                  - type: Resource
                                    resource:
                                      name: cpu
                                      target:
                                        type: Utilization
                                        averageUtilization: 70"""),
                        text("ro:math", """
                                The math runs against the Deployment's resource REQUESTS (250m per pod in deployment.yml): two pods at 70% average means 350m observed against 500m requested — the controller adds a third when utilization crosses the target, and the scaleDown stabilization window (five minutes in the manifest) keeps a traffic dip from thrashing the fleet down and back. The vertical pair of knobs sits beside it: REQUESTS are what the scheduler reserves and what drives bin-packing; LIMITS are the ceiling where the kernel throttles CPU or OOMKills memory — set both, and set them from MEASUREMENT (the performance topic's JFR and load profiles), because a request too low starves the pod under contention and a limit too low turns every traffic spike into an OOMKill loop that looks mysteriously like a crash bug. The honest summary: maxSurge, maxUnavailable, the HPA bounds, and the stabilization window are four numbers that encode your deploy's risk appetite — tune them on purpose, and read them before every "Kubernetes dropped traffic during rollout" postmortem; it is usually one of the four.""")),
                section("infrastructure-as-code", "Infrastructure as code",
                        "Compose files, k8s manifests, the devcontainer: every environment this repo has is a versioned, reviewed file. The Service and Ingress finish the set.",
                        text("ic:principle", """
                                Count the infrastructure this guide has already shipped as FILES: a devcontainer definition, two compose stacks, three Dockerfiles, an nginx config, six k8s manifests — and exactly ZERO wiki pages of setup instructions. That is infrastructure as code, and its value is not aesthetics: a file can be REVIEWED (the parity section's "no unnamed differences" rule is enforced by diff), VERSIONED (a broken change reverts like code), and REPLAYED (a new cluster, a new teammate, a disaster recovery — same files, same world). The environments topic called devcontainer.json "environment as code"; deployment generalizes it: every environment from laptop to cluster is a file in the repo, and the terminal companion tours them the way you actually would — read the file, ask the running world how it differs.

                                The two k8s objects not yet quoted complete the cluster path. The Service is the stable front door — a DNS name that load-balances across whatever pods currently match, so rollouts can churn pods underneath while clients keep one address:"""),
                        code("ic:service", "yaml",
                                "deploy/k8s/service.yml — the stable name: port 80 to the pods' named http port, selector-matched",
                                "deploy/k8s/service.yml", """
                                spec:
                                  type: ClusterIP
                                  selector:
                                    app.kubernetes.io/name: springboot-developers-guide
                                    app.kubernetes.io/component: server
                                  ports:
                                    - name: http
                                      port: 80
                                      targetPort: http"""),
                        text("ic:edge", """
                                And the Ingress is the edge — the platform's version of the reverse-proxy section, TLS termination and name-based routing done by the cluster's ingress controller instead of a container you run:"""),
                        code("ic:ingress", "yaml",
                                "deploy/k8s/ingress.yml — the edge as an object: TLS secret, one host, one path to the Service",
                                "deploy/k8s/ingress.yml", """
                                spec:
                                  ingressClassName: nginx
                                  tls:
                                    - hosts:
                                        - guide.example.com
                                      secretName: server-tls
                                  rules:
                                    - host: guide.example.com
                                      http:
                                        paths:
                                          - path: /
                                            pathType: Prefix
                                            backend:
                                              service:
                                                name: server
                                                port:
                                                  name: http"""),
                        text("ic:honesty", """
                                Honesty about the limits, because IaC also has a failure mode: files that DESCRIBE a world nobody applied, or a cluster someone hand-patched afterwards — DRIFT. The tools at the next tier exist for exactly that: Helm (parameterize the manifest set per environment), Kustomize (overlay the differences without templating), and Terraform or Pulumi one level up (the cluster, the database, the DNS record as code too — the provisioning layer this guide stops short of, deliberately: at this scale, plain manifests ARE the right tool, and graduating to Helm is a decision to make when the third environment appears, not before). The rule that survives every tool choice: if a change to production happened outside a reviewed file — a kubectl edit, a console click, an SSH session — the very next act is reconciling the file or reverting the change; the file is the truth, the cluster is just its current opinion.""")),
                section("linux-survival", "Linux survival kit",
                        "The production shell is Linux, whether you arrive by SSH, kubectl exec, or a platform's console. The twenty commands that answer ninety percent of incidents.",
                        text("lx:why", """
                                Every layer of this topic eventually bottoms out in a Linux shell: the container runs Linux, the kubelet runs Linux, and the day something is wrong in production, you will read Linux's answers. The survival kit is small — the goal is not sysadmin mastery but FLUENCY in the questions that recur. WHAT IS RUNNING: ps aux (every process, its user, its memory), ps aux | grep java to find yours; top (or htop) for the live view — load average first (roughly: runnable work per core; sustained numbers above your core count mean saturation), then per-process CPU and RES memory. WHERE DID IT GO: df -h (disk full is the most anticlimactic root cause in history — a full disk fills logs first), du -sh for the per-directory hunt; free -h for memory, where available matters and buff/cache is not "used". WHY DID IT DIE: dmesg | tail for the kernel's verdict (OOMKills appear here — a process killed by the kernel for exceeding its memory limit looks like a crash and is a LIMITS problem, the rollouts section's warning in native form), journalctl -u <service> for systemd units' logs, docker logs and kubectl logs for containers' stdout.

                                SIGNALS deserve their own paragraph because they are the deployment topic's native tongue: kill <pid> sends SIGTERM (the graceful shutdown trigger — the same signal compose, k8s, and systemd send), kill -9 sends SIGKILL (uncatchable, no drain, the thing the 45-second grace period exists to make unnecessary), and kill -l lists the rest. If you remember one debugging sequence, make it this: ps aux | grep java to find the PID, kill -3 <pid> to make the JVM dump every thread to stdout (the debugging topic's thread dump — readable in the logs, no tooling required), and only then consider stronger signals. NETWORKING is the last third of the kit: ss -tlnp (which process listens on which port — the "address already in use" resolver), curl -v (the topic's constant companion — every tour in this guide is curl), and ping or nc for reachability. And the one habit that outranks all the commands: when a box misbehaves, READ THE LOGS BEFORE RESTARTING — a restart destroys the evidence (heap state, connection counts, the log's last words) that would have explained the misbehavior. You can always restart AFTER you have looked.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic, curled: the demo's endpoint, then the three tours — docker, kubectl, curl — against the running stack.",
                        text("tc:intro", """
                                Every screen in this topic has a terminal twin. The demo's endpoint is one thin GET — the same controller shape as every demo in the guide:"""),
                        code("tc:controller", "java",
                                "guide/presentation/demo/DeployDemoController.java — one endpoint, one use case, already the wire shape",
                                "server/src/main/java/guide/presentation/demo/DeployDemoController.java", """
                                @GetMapping(value = "/api/demo/deploy/stack", produces = MediaType.APPLICATION_JSON_VALUE)
                                DeploymentInspection stack() {
                                    return inspectDeployment.execute();
                                }"""),
                        code("tc:client", "ts",
                                "client/src/ui/demos/deployment/deploy_stack_demo.tsx — the demo's load: one GET, typed parse, guarded like every demo",
                                "client/src/ui/demos/deployment/deploy_stack_demo.tsx", """
                                /** The whole deployment story in one GET — deterministic process state. */
                                const loadStack = useCallback(() => {
                                  setPanel({ kind: 'loading' });
                                  void safeRepositoryCall(async () =>
                                    parseStack(await requestJson(config, '/demo/deploy/stack')),
                                  ).then((result) => {
                                    if (result.ok) {
                                      setStack(result.value);
                                      setPanel({ kind: 'idle' });
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                }, [config]);"""),
                        text("tc:tour", """
                                Then the tours. The docker tour walks the compose stack the demo mirrors; the kubectl tour walks the manifest set this topic ships (a local kind or minikube cluster with the metrics-server addon is enough to apply it); the curl tour asks the running process the deployment questions:"""),
                        code("tc:docker-tour", "bash",
                                "the docker/compose tour",
                                null, """
                                # the stack the demo mirrors, from the host:
                                docker compose -f .docker/docker-compose.yml ps
                                # NAME ... STATUS "healthy" — the healthchecks at work

                                # what the server container actually receives (the env rails):
                                docker compose -f .docker/docker-compose.yml exec server printenv | grep -E 'SPRING_|GUIDE_'
                                # SPRING_PROFILES_ACTIVE=dev
                                # GUIDE_DEPLOY_ENV=dev
                                # SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/guide

                                # a graceful shutdown, observed — stop the stack and read the logs:
                                docker compose -f .docker/docker-compose.yml logs server | tail -4
                                # ...Commencing graceful shutdown. Shutdown in progress...
                                # ...Graceful shutdown complete

                                # the image side: layers are the cache, cache is the speed
                                docker image inspect sbdg_server_prod --format '{{.RootFS.Layers}}' | tr ' ' '\\n' | wc -l
                                # a small count — few layers, ordered least-changed first"""),
                        code("tc:kubectl-tour", "bash",
                                "the kubectl tour",
                                null, """
                                # apply the whole manifest set this topic ships:
                                kubectl apply -f deploy/k8s/
                                # namespace/sbdg-guide created
                                # deployment.apps/server created · service/server created ...

                                # watch the rollout: surge pod starting, readiness gating, old pod draining
                                kubectl -n sbdg-guide rollout status deployment/server
                                # deployment "server" successfully rolled out

                                # the probe pair from inside the cluster:
                                kubectl -n sbdg-guide run curl --rm -it --image=curlimages/curl -- \\
                                  curl -s http://server/actuator/health/readiness
                                # {"status":"UP"}

                                # scale by hand once, then let the HPA own it:
                                kubectl -n sbdg-guide scale deployment/server --replicas=3
                                kubectl -n sbdg-guide get hpa
                                # ... references Deployment/server, targets 70% avg on cpu

                                # the rollback verb the immutability story buys you:
                                kubectl -n sbdg-guide rollout history deployment/server
                                kubectl -n sbdg-guide rollout undo deployment/server"""),
                        code("tc:curl-tour", "bash",
                                "the curl tour",
                                null, """
                                # the demo endpoint, from a terminal:
                                curl -s localhost:8080/api/demo/deploy/stack | python3 -m json.tool | head -20
                                # {
                                #   "container": {"image": "sbdg_server_prod",
                                #     "base": "eclipse-temurin:25-jre-noble", "port": 8080, ...},
                                #   "composeServices": [
                                #     {"name": "dev", "image": "sbdg_dev_toolchain", "ports": [], ...},
                                #     {"name": "server", "image": "maven:3.9-eclipse-temurin-25", ...}, ...],
                                #   "envRails": [{"name": "SPRING_PROFILES_ACTIVE",
                                #     "devValue": "dev", "prodValue": "(unset -> default)", ...}, ...]

                                # the shutdown posture, read from the live process:
                                curl -s localhost:8080/api/demo/deploy/stack | grep -A3 '"shutdown"'
                                # "mode": "graceful", "timeout": "30s", ...

                                # the probe pair the orchestrator polls (no orchestrator needed):
                                curl -s localhost:8080/actuator/health/liveness
                                # {"status":"UP"}
                                curl -s localhost:8080/actuator/health/readiness
                                # {"status":"UP"}

                                # behind the edge proxy, the same answers via nginx:
                                curl -s localhost:8081/api/demo/deploy/stack | head -c 120"""),
                        text("tc:close", """
                                That last hop is the topic in miniature: same build, same endpoints, one more network hop owned by a config file. You now hold the whole road — an image that ships only what it needs, a stack that boots in order and shuts down gracefully, a pipeline that gates and promotes the same bytes, parity you can list, secrets that never ride plaintext, a cluster that rolls and scales on declared state, and the shell fluency to check every link of it. The next topic takes the running system and asks the question this one kept deferring: how fast is it, and how do you know — performance, measured before tuned.""")));
    }
}

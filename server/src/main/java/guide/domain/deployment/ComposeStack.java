package guide.domain.deployment;

import java.util.List;

/**
 * A typed mirror of {@code .docker/docker-compose.yml} (the dev stack) — the
 * demo behind topic 37 serves the deployment story from THIS record instead
 * of parsing yaml at runtime, so the answer is deterministic and the mirror
 * documents the stack the way the compose file spells it. When the compose
 * file changes, this record changes with it (the topic's snippet discipline
 * keeps the teaching honest; the demo is the readable twin).
 *
 * Six services, three kinds: tooling (dev — runs no app), app processes
 * (server, client), and data services (postgres required-by-convention,
 * redis + mongo optional by the degrade-gracefully discipline).
 */
public final class ComposeStack {

    private ComposeStack() {
    }

    /** The dev stack, exactly as {@code .docker/docker-compose.yml} declares it. */
    public static List<DeploymentInspection.ServiceLine> devServices() {
        return List.of(
                new DeploymentInspection.ServiceLine("dev", "sbdg_dev_toolchain", List.of(),
                        "the devcontainer tooling container — JDK 25 + Maven + Node 22; runs no app"),
                new DeploymentInspection.ServiceLine("server", "maven:3.9-eclipse-temurin-25",
                        List.of("8080:8080"),
                        "Spring Boot dev server — mvn spring-boot:run on the bind-mounted source, devtools hot reload"),
                new DeploymentInspection.ServiceLine("client", "node:22-bookworm", List.of("5176:5176"),
                        "Vite dev server — installs from the lockfile, proxies /api to the server service"),
                new DeploymentInspection.ServiceLine("postgres", "postgres:17", List.of("5432:5432"),
                        "the guide's only SQL engine (no H2: one dialect)"),
                new DeploymentInspection.ServiceLine("redis", "redis:7-alpine", List.of("6379:6379"),
                        "optional service — cache/session/pub-sub; the app degrades gracefully when absent"),
                new DeploymentInspection.ServiceLine("mongo", "mongo:8", List.of("27017:27017"),
                        "optional service — the mongodb topic's engine, same optional deal as redis"));
    }

    /**
     * The environment rails the compose stacks set — the twelve-factor proof
     * this demo exists to render. devValue is what docker-compose.yml sets;
     * prodValue is what docker-compose.prod.yml sets (unset values fall to
     * the application.yml default, shown in parentheses).
     */
    public static List<DeploymentInspection.EnvRail> envRails() {
        return List.of(
                new DeploymentInspection.EnvRail("SPRING_PROFILES_ACTIVE", "dev", "prod",
                        "dev boots the dev profile (GraphiQL mounted); prod boots the prod profile and refuses to start without SDG_JWT_SECRET + a real DB password (ProdSanityConfig)"),
                new DeploymentInspection.EnvRail("GUIDE_DEPLOY_ENV", "dev", "(unset → local)",
                        "topic 36's inspector rail — same build, different answer per environment"),
                new DeploymentInspection.EnvRail("SPRING_DATASOURCE_URL",
                        "jdbc:postgresql://postgres:5432/guide", "jdbc:postgresql://postgres:5432/guide",
                        "parity: identical in both stacks — the compose network's service name resolves in each"),
                new DeploymentInspection.EnvRail("SPRING_DATA_MONGODB_URI",
                        "mongodb://mongo:27017/guide", "mongodb://mongo:27017/guide",
                        "parity: identical — the optional services ride the same rails everywhere"),
                new DeploymentInspection.EnvRail("SPRING_DATA_REDIS_HOST", "redis", "redis",
                        "parity: identical"));
    }
}

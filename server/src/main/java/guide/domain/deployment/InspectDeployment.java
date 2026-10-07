package guide.domain.deployment;

import java.util.List;
import java.util.Objects;

/**
 * Use case behind the deployment-story demo (topic 37): assemble the
 * deployment inventory from two inputs — the typed {@link ComposeStack}
 * mirror of the repo's compose files, and the graceful-shutdown posture read
 * once from the real {@code Environment} at the composition root (so the
 * answer reports what the PROCESS carries, not a hardcoded string).
 *
 * The probe pair mirrors deploy/k8s/deployment.yml and the actuator config
 * that serves it: liveness says "restart me", readiness says "stop routing
 * to me" — the one distinction a rollout depends on.
 */
public final class InspectDeployment {

    /** Mirrors deploy/k8s/deployment.yml's livenessProbe + readinessProbe blocks. */
    static final DeploymentInspection.ProbeLine PROBES =
            new DeploymentInspection.ProbeLine("/actuator/health/liveness", "/actuator/health/readiness", 8080);

    /** Mirrors the shutdown contract the k8s termination choreography bets on. */
    static final String DRAIN_CONTRACT =
            "SIGTERM → stop accepting new connections → finish in-flight requests (bounded by the timeout) "
                    + "→ context close; k8s's terminationGracePeriodSeconds (45s) outlives the 30s app budget";

    private final ShutdownPosture shutdownPosture;

    public InspectDeployment(ShutdownPosture shutdownPosture) {
        this.shutdownPosture = Objects.requireNonNull(shutdownPosture, "shutdownPosture");
    }

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
    }

    /** Narration lines: the report explains itself the way the topic's prose does. */
    private List<String> reading() {
        return List.of(
                "one build, many environments: the env rails above are the WHOLE difference between dev "
                        + "and prod compose — the image is built once and never rebuilt per target",
                "parity rails (datasource, mongo, redis) carry IDENTICAL values in both stacks; the "
                        + "deliberate differences (profile, deploy env) are values that NAME the environment",
                "the probes are the actuator's k8s pair: liveness = restart me, readiness = stop routing "
                        + "to me — deployment.yml's rollout surges a new pod and only routes to it when "
                        + "readiness turns green",
                "shutdown posture is read from the live process: server.shutdown=" + shutdownPosture.mode()
                        + " with a " + shutdownPosture.timeout() + " drain budget per phase");
    }
}

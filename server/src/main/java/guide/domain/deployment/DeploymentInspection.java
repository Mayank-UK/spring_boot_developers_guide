package guide.domain.deployment;

import java.util.List;

/**
 * The deployment-story demo's answer (topic 37, demo {@code deploy:stack}):
 * a readable inventory of how THIS repo ships — the container the prod image
 * runs, the compose stack it grows up in, the environment rails that make
 * one build serve every environment, the probe pair the orchestrator polls,
 * and the graceful-shutdown posture the running process actually carries.
 *
 * Everything is process state or a typed mirror of a real repo file — never
 * a live dump: the answer is deterministic by construction.
 */
public record DeploymentInspection(
        ContainerLine container,
        List<ServiceLine> composeServices,
        List<EnvRail> envRails,
        ProbeLine probes,
        ShutdownLine shutdown,
        List<String> reading) {

    /** The prod container: what the image is and how it starts (Dockerfile.server.prod). */
    public record ContainerLine(String image, String base, String entrypoint, int port) {
    }

    /** One compose service: name, image, published ports (host:container), its role. */
    public record ServiceLine(String name, String image, List<String> ports, String role) {
    }

    /** One environment rail: the env var name, what dev sets, what prod sets, the lesson. */
    public record EnvRail(String name, String devValue, String prodValue, String note) {
    }

    /** The health-probe pair the orchestrator polls (deploy/k8s/deployment.yml). */
    public record ProbeLine(String livenessPath, String readinessPath, int port) {
    }

    /** The graceful-shutdown posture as the running process carries it. */
    public record ShutdownLine(String mode, String timeout, String contract) {
    }
}

package guide.presentation.demo;

import guide.domain.deployment.DeploymentInspection;
import guide.domain.deployment.InspectDeployment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for topic 37 (deployment) — the deployment story, live:
 *
 * - GET /api/demo/deploy/stack → the inventory of how this repo ships: the
 *   prod container (image, base, launcher, port), the six-service dev
 *   compose stack as a typed mirror of .docker/docker-compose.yml, the
 *   twelve-factor env rails with dev vs prod values side by side (the
 *   one-build-many-environments proof), the actuator probe pair the k8s
 *   Deployment polls, and the graceful-shutdown posture read from the real
 *   property sources at boot.
 *
 * Thin as always: one endpoint, one use case call, no mapping logic — the
 * inspection is already the wire shape. Deterministic: every input is
 * process state or a typed mirror that exists before the first request.
 */
@RestController
public class DeployDemoController {

    private final InspectDeployment inspectDeployment;

    public DeployDemoController(InspectDeployment inspectDeployment) {
        this.inspectDeployment = inspectDeployment;
    }

    @GetMapping(value = "/api/demo/deploy/stack", produces = MediaType.APPLICATION_JSON_VALUE)
    DeploymentInspection stack() {
        return inspectDeployment.execute();
    }
}

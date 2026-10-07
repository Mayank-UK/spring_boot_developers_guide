package guide.domain.deployment;

/**
 * The graceful-shutdown posture as the running process carries it — read
 * once from the real {@code Environment} at the composition root
 * (DeployDemoConfig), so the demo reports what Boot actually bound from
 * application.yml, never a hardcoded copy of it.
 *
 * mode — {@code server.shutdown}: "graceful" (drain in-flight requests on
 * SIGTERM, the production posture this repo sets) or Boot's default
 * "immediate" (stop at once; every in-flight response dies mid-stream).
 * timeout — {@code spring.lifecycle.timeout-per-shutdown-phase}: the per-phase
 * budget before the context forces shutdown onward.
 */
public record ShutdownPosture(String mode, String timeout) {
}

package guide.domain.jpa;

import java.util.Map;
import java.util.Objects;

/**
 * The optimistic-locking demo's use case. Two surfaces, one story:
 * report (default) returns what happened to both writers; envelope re-runs
 * the race and then rethrows the losing writer's outcome as the guide's own
 * 409 CONFLICT — proving the mapping from Spring's
 * OptimisticLockingFailureException into the one error taxonomy, the same
 * discipline the JdbcTemplate path enforced by hand in topic 17.
 */
public final class RunLockingProbe {

    /** Whether the endpoint should end with the real 409 envelope or the JSON story. */
    public enum Surface { REPORT, ENVELOPE }

    private final JpaProbePort port;

    public RunLockingProbe(JpaProbePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /** surface optional (default report); unknown words are validation failures. */
    public JpaProbePort.LockingReport run(String surfaceWire) {
        Surface surface = Surface.REPORT;
        if (surfaceWire != null && !surfaceWire.isBlank()) {
            try {
                surface = Surface.valueOf(surfaceWire.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException bad) {
                throw guide.core.errors.AppException.of(guide.core.errors.Failure.of(
                        guide.core.errors.FailureKind.VALIDATION,
                        "surface must be \"report\" or \"envelope\"", Map.of("parameter", "surface")));
            }
        }
        JpaProbePort.LockingReport report = port.lockRace();
        if (surface == Surface.ENVELOPE) {
            // The losing writer's outcome, re-surfaced as OUR envelope: this is
            // exactly the catch-and-map the adapter performed internally, shown
            // on the wire so curl can see the 409 shape it produces.
            throw new guide.core.errors.ConflictException(
                    "optimistic lock rejected the stale writer: "
                            + report.rejectionType() + " — " + report.note(),
                    Map.of("postId", report.postId(),
                            "currentVersion", report.winningVersion(),
                            "mappedFrom", report.rejectionType()));
        }
        return report;
    }
}

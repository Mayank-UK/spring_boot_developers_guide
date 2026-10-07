package guide.domain.jpa;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import java.util.Map;
import java.util.Objects;

/**
 * Topic 18's probe use case: validate the fetch-shape request, hand it to the
 * port, return the measurement. The bounds are part of the lesson's honesty —
 * a page small enough to read by eye (2..12 posts), because the verdict lines
 * are written for numbers you can verify in your head (5 posts, 6 queries).
 */
public final class RunJpaProbe {

    /** The default specimen page — small enough that N+1 reads instantly. */
    public static final int DEFAULT_SIZE = 5;

    private final JpaProbePort port;

    public RunJpaProbe(JpaProbePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /** mode is required; size optional (default 5, bounds 2..12). */
    public JpaProbePort.ProbeReport run(String modeWire, Integer size) {
        Objects.requireNonNull(modeWire, "modeWire");
        ProbeMode mode;
        try {
            mode = ProbeMode.fromWire(modeWire);
        } catch (IllegalArgumentException bad) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    bad.getMessage(), Map.of("parameter", "mode")));
        }
        int resolved = size == null ? DEFAULT_SIZE : size;
        if (resolved < 2 || resolved > 12) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "size must be between 2 and 12 — the probe teaches with a page you can count by eye",
                    Map.of("parameter", "size")));
        }
        return port.probe(mode, resolved);
    }
}

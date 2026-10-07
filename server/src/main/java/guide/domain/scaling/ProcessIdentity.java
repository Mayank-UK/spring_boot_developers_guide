package guide.domain.scaling;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;

/**
 * The REAL identity of this JVM process — the thing every replica in a
 * scaled fleet can report about itself (topic 40, scaling). The
 * RuntimeMXBean's name is {@code <pid>@<host>}: exactly what makes "which
 * instance served me" an observable fact instead of a guess, the same
 * string {@code jcmd} lists and pod logs correlate on. In production the
 * identity is usually enriched with the pod name and namespace
 * (an env var the Deployment injects); the principle is unchanged —
 * identity is DERIVED at runtime, never configured by hand, so a restart
 * or a reschedule can never serve stale claims about who it is.
 */
public record ProcessIdentity(String instanceId, Instant startedAt) {

    /** pid@host plus the JVM's boot instant, read once from the runtime. */
    public static ProcessIdentity current() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        return new ProcessIdentity(runtime.getName(),
                Instant.ofEpochMilli(runtime.getStartTime()));
    }
}

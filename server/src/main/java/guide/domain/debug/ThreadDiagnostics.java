package guide.domain.debug;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * In-process thread diagnostics over the JVM's own {@link ThreadMXBean} —
 * the exact machinery {@code jstack} and {@code jcmd Thread.print} read from
 * the outside, exercised from inside the process. Pure JDK: no framework, no
 * external process, safe to call on any thread at any time (the MXBean's
 * snapshot operations are not safepoint-blocking).
 *
 * This class is the topic's bridge from DEMO to TOOL: what it returns is
 * byte-for-byte the shape a real thread dump prints — name, state, lock,
 * lock owner, stack top-down — so the panel you read here is the skill you
 * use on a real {@code .dump} file.
 */
public final class ThreadDiagnostics {

    /** Stack depth cap per thread: enough for the story, bounded for the wire. */
    static final int MAX_STACK_DEPTH = 16;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    /** One thread as a dump would show it; null when the thread has already died. */
    public StallThreadView snapshotOf(Thread thread) {
        ThreadInfo info = threads.getThreadInfo(thread.getId(), MAX_STACK_DEPTH);
        if (info == null) {
            return null; // died between listing and asking — report nothing, honestly
        }
        String[] frames = new String[info.getStackTrace().length];
        for (int i = 0; i < info.getStackTrace().length; i++) {
            StackTraceElement element = info.getStackTrace()[i];
            frames[i] = "at " + element.getClassName() + "." + element.getMethodName()
                    + "(" + element.getFileName() + ":" + element.getLineNumber() + ")";
        }
        return new StallThreadView(info.getThreadName(), info.getThreadState().toString(),
                info.getLockName(), info.getLockOwnerName(), List.of(frames));
    }

    /** How many live threads the JVM is running — the honest size of a real dump. */
    public int liveThreadCount() {
        return threads.getThreadCount();
    }

    /** Snapshot helper for a fixed set of named threads, dropping any that died. */
    public List<StallThreadView> snapshotAll(List<Thread> watched) {
        return watched.stream()
                .map(this::snapshotOf)
                .filter(view -> view != null)
                .toList();
    }
}

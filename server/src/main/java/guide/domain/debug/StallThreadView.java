package guide.domain.debug;

import java.util.List;

/**
 * One thread as a thread dump would show it: the name (THE identifier you
 * grep for), the {@link Thread.State} spelled exactly as {@code jstack}
 * prints it, the monitor it is waiting to lock (when BLOCKED) or waiting on
 * (when in {@code Object.wait}) with the owning thread's name, and the stack
 * frames top-down. This is the shape {@code ThreadMXBean} already answers
 * in-process — the demo renders it instead of pretending to run jstack.
 */
public record StallThreadView(String name, String state, String lock, String lockOwner, List<String> stack) {

    public StallThreadView {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(state, "state");
        stack = stack == null ? List.of() : List.copyOf(stack);
    }
}

package guide.domain.teaching;

/**
 * Teaching exhibit for topic 02 (jvm), process/env/signals section: the
 * shutdown-hook API — the place graceful shutdown begins. On SIGTERM (or
 * normal exit) the JVM runs every registered hook before exiting; that is
 * the last code you control before the process dies, and it is where a
 * server flushes buffers, closes connections, and tells the load balancer
 * goodbye.
 *
 * A registered hook cannot be PREVIEWED without exiting the JVM, so the
 * exhibit exercises the registration mechanics safely: register, then REMOVE
 * — and reports whether the removal worked (removeShutdownHook is also how
 * frameworks cancel hooks they no longer want, e.g. on a failed startup
 * rollback). The hook body is one line of real cleanup code; it never runs
 * here because the hook is removed in the same breath.
 */
public final class ExhibitShutdownSignal {

    /** Register → remove: the full hook lifecycle without exiting the JVM. */
    public static String registerAndRemove(String label) {
        String name = "shutdown-" + (label == null || label.isBlank() ? "demo" : label.trim());
        Thread hook = new Thread(() -> System.out.println("SIGTERM cleanup: " + name), name);
        Runtime runtime = Runtime.getRuntime();
        runtime.addShutdownHook(hook);
        boolean removed = runtime.removeShutdownHook(hook);
        return "hook \"" + name + "\" registered"
                + (removed ? ", then removed — it will not run at exit" : " — removeShutdownHook returned false");
    }
}

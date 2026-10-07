package guide.domain.errors;

import guide.core.logging.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The process-level safety net: a JVM-wide default uncaught-exception
 * handler, registered once at boot by ErrorsDemoConfig. When a thread dies
 * with an exception NOBODY caught — a fired-and-forged Runnable, a virtual
 * thread past its request scope — this logs it with the request id (from
 * MDC when the dying thread still carries one, "n/a" otherwise) instead of
 * letting it vanish into the container's stderr.
 *
 * It does NOT swallow: after logging, it delegates to the handler that was
 * installed before us (the JVM's default prints to stderr — that behavior
 * survives), and the thread still dies. An uncaught handler cannot rescue a
 * thread; it can only make the death legible. Idempotent install guards
 * against repeated context loads (tests) chaining the same logger twice.
 */
public final class UncaughtFailureLogger implements Thread.UncaughtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UncaughtFailureLogger.class);

    private final Thread.UncaughtExceptionHandler previous;

    UncaughtFailureLogger(Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
    }

    /**
     * Installs the handler as the JVM default and returns it. Re-install is
     * a no-op returning the already-installed instance, so repeated context
     * loads (tests, child contexts) never chain duplicates.
     */
    public static UncaughtFailureLogger install() {
        Thread.UncaughtExceptionHandler existing = Thread.getDefaultUncaughtExceptionHandler();
        if (existing instanceof UncaughtFailureLogger already) {
            return already;
        }
        UncaughtFailureLogger handler = new UncaughtFailureLogger(existing);
        Thread.setDefaultUncaughtExceptionHandler(handler);
        return handler;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        log.error("uncaught exception (process net): thread={} requestId={}",
                thread.getName(), requestId != null ? requestId : "n/a", throwable);
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        }
    }
}

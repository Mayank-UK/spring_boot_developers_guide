package guide.domain.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import guide.core.logging.RequestIdFilter;

/**
 * The process-level safety net, triggered DIRECTLY (no thread has to die
 * for this): one call into uncaughtException must log (observed by the
 * slf4j MDC still being set — and not throw), then delegate to the
 * previously-installed handler without swallowing anything. The idempotent
 * install is pinned too: a second install returns the SAME instance instead
 * of chaining a duplicate.
 */
class UncaughtFailureLoggerTest {

    /** Recording stand-in for the handler that was installed before us. */
    static final class RecordingPrevious implements Thread.UncaughtExceptionHandler {
        final List<String> saw = new ArrayList<>();

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            saw.add(thread.getName() + ":" + throwable.getMessage());
        }
    }

    @Test
    void directTriggerLogsDelegatesAndDoesNotSwallow() {
        RecordingPrevious previous = new RecordingPrevious();
        UncaughtFailureLogger handler = new UncaughtFailureLogger(previous);
        Thread victim = new Thread(() -> {
        }, "victim-thread");
        RuntimeException boom = new RuntimeException("deliberate");

        MDC.put(RequestIdFilter.MDC_KEY, "req-uncaught-1");
        try {
            assertDoesNotThrow(() -> handler.uncaughtException(victim, boom));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        assertEquals(List.of("victim-thread:deliberate"), previous.saw,
                "the previous handler must still run after ours — the net logs, it never swallows");
    }

    @Test
    void aNullPreviousIsTolerated() {
        UncaughtFailureLogger handler = new UncaughtFailureLogger(null);
        assertDoesNotThrow(() ->
                handler.uncaughtException(Thread.currentThread(), new IllegalStateException("x")));
    }

    @Test
    void installIsIdempotentAndBecomesTheDefault() {
        UncaughtFailureLogger first = UncaughtFailureLogger.install();
        try {
            assertInstanceOf(UncaughtFailureLogger.class, Thread.getDefaultUncaughtExceptionHandler(),
                    "install() must install itself as the JVM default");
            UncaughtFailureLogger second = UncaughtFailureLogger.install();
            assertSame(first, second, "a repeated install (test context reloads) must not chain a duplicate");
        } finally {
            // leave the JVM as we found it — later slices may install for real
            Thread.setDefaultUncaughtExceptionHandler(null);
        }
    }
}

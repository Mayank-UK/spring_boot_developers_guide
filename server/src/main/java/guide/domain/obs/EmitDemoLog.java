package guide.domain.obs;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Use case: deliberately log one event at a requested level, then prove it
 * landed by reading it back from the ring. The tour's emit buttons and
 * {@code /api/demo/obs/logs/emit} both run through here.
 *
 * The emitter's logger carries its own TRACE level (set once, below): the
 * root logger sits at INFO like a production process, and a demo that wanted
 * trace-level events in the ring would otherwise silently drop them at the
 * logger — the exact "why is my log line not there" confusion per-logger
 * levels cause in real apps, met head-on instead of hidden.
 */
public final class EmitDemoLog {

    /** The emitter's dedicated logger — its level is TRACE, independent of root. */
    public static final String LOGGER_NAME = "guide.demo.obs.emitter";

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    static {
        ch.qos.logback.classic.Logger logback =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LOGGER_NAME);
        logback.setLevel(ch.qos.logback.classic.Level.TRACE);
    }

    private final LogRing ring;
    private final ObsMetrics metrics;

    public EmitDemoLog(LogRing ring, ObsMetrics metrics) {
        this.ring = ring;
        this.metrics = metrics;
    }

    /** Emits one event at the requested level; the answer is what the RING recorded. */
    public Result<LoggedEvent> execute(String level) {
        String normalized = level == null ? "" : level.trim().toLowerCase(Locale.ROOT);
        Level parsed;
        try {
            parsed = Level.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notALevel) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "unknown log level \"" + normalized + "\" — the five are "
                            + String.join(", ", ObsMetrics.EMIT_LEVELS),
                    java.util.Map.of("field", "level")));
        }
        String message = "deliberate " + parsed + " emission from the observability tour";
        LOGGER.atLevel(parsed).log(message);
        metrics.emitted(normalized);
        return Result.success(newestMatching(message, parsed.toString()));
    }

    /** Reads the just-emitted event back from the ring — proof the pipeline carried it. */
    private LoggedEvent newestMatching(String message, String level) {
        List<LoggedEvent> recent = ring.recent(level, 10);
        for (LoggedEvent event : recent) {
            if (message.equals(event.message())) {
                return event;
            }
        }
        // The ring is a second destination, not a guarantee: if no appender is
        // attached (a context that never processed logback-spring.xml), report
        // what WOULD have been logged rather than pretending it was captured.
        return new LoggedEvent(System.currentTimeMillis(), level, LOGGER_NAME, message, null, null);
    }
}

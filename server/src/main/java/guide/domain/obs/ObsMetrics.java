package guide.domain.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This app's own meters, in one place, on the Micrometer API (a library, not
 * a framework — the domain stays Spring-free). Everything here answers ONE
 * question of the three: "is it right, is it fast, and how much of it is
 * happening?" — business beats (posts created, job outcomes, deliberate
 * emissions) and the demo request timer the request-logging filter records.
 *
 * Two disciplines the topic teaches, both visible in this file:
 *
 * - TAG SETS ARE PRE-INITIALIZED. A counter with tags does not exist until
 *   first increment, so an exposition only shows {@code outcome="succeeded"}
 *   until the first real death. Alerting on a series that appears late (or
 *   never, because the code path is dead) is a classic trap; declaring the
 *   full tag family up front makes "zero dead-letter jobs" an OBSERVABLE
 *   zero, not an absent series.
 *
 * - REGISTRATION IS IDEMPOTENT. {@code registry.counter(...)} returns the
 *   same meter for the same name+tags on every call, so nothing here breaks
 *   when a context refreshes; there is no "register once" bookkeeping to get
 *   wrong.
 */
public final class ObsMetrics {

    /** The full outcome family, wire names from topic 29's JobRecord outcomes. */
    public static final String[] JOB_OUTCOMES = {"succeeded", "failed", "dead"};

    /** The demo request timer's outcome family (HTTP status classes, not raw statuses — cardinality discipline). */
    public static final String[] REQUEST_OUTCOMES = {"success", "client_error", "server_error"};

    /** The emitter's level family — the five levels the tour can deliberately emit. */
    public static final String[] EMIT_LEVELS = {"trace", "debug", "info", "warn", "error"};

    private final MeterRegistry registry;

    public ObsMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String outcome : JOB_OUTCOMES) {
            jobOutcomeCounter(outcome);
        }
        // the single-tagless counter pre-initializes the same way: a series
        // that exists at zero beats an absent series the dashboard must guess about
        registry.counter("guide.posts.created");
        for (String outcome : REQUEST_OUTCOMES) {
            demoTimer(outcome);
        }
        for (String level : EMIT_LEVELS) {
            emittedCounter(level);
        }
    }

    /** A real post was created through the posts use case (the trace hop's business step bumps this). */
    public void postCreated() {
        registry.counter("guide.posts.created").increment();
    }

    /** A job attempt settled into a terminal or retry outcome (topic 29's outcome vocabulary). */
    public void jobOutcome(String outcomeWireName) {
        jobOutcomeCounter(outcomeWireName).increment();
    }

    /** The emitter deliberately logged at this level (the viewer's emit buttons bump this). */
    public void emitted(String level) {
        emittedCounter(level.toLowerCase(java.util.Locale.ROOT)).increment();
    }

    /** One /api/demo request completed; duration from the request-logging filter's own stopwatch. */
    public void recordDemoRequest(Duration duration, String outcome) {
        demoTimer(outcome).record(duration);
    }

    /**
     * The curated snapshot the summary endpoint serves: every {@code guide.*}
     * meter with its type, tags, and the readings that matter for that type
     * (count for counters, value for gauges, count/mean/max/p95 for timers).
     * Cumulative counters, rates computed by the scraper — the topic's
     * rate-vs-cumulative point, live.
     */
    public List<MeterRow> snapshot() {
        List<MeterRow> rows = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!name.startsWith("guide.")) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            if (meter instanceof Counter counter) {
                values.put("count", counter.count());
            } else if (meter instanceof Timer timer) {
                values.put("count", timer.count());
                values.put("meanMs", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
                values.put("maxMs", timer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
                values.put("p95Ms", timer.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS));
            } else if (meter instanceof io.micrometer.core.instrument.Gauge gauge) {
                values.put("value", gauge.value());
            } else {
                continue;
            }
            Map<String, String> tags = new LinkedHashMap<>();
            meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
            rows.add(new MeterRow(name, meter.getId().getType().toString().toLowerCase(java.util.Locale.ROOT),
                    tags, values));
        }
        rows.sort(java.util.Comparator.comparing(MeterRow::name));
        return List.copyOf(rows);
    }

    private Counter jobOutcomeCounter(String outcome) {
        return Counter.builder("guide.jobs.outcomes").tag("outcome", outcome)
                .description("Background job attempt outcomes (topic 29's vocabulary)")
                .register(registry);
    }

    private Counter emittedCounter(String level) {
        return Counter.builder("guide.obs.emitted").tag("level", level)
                .description("Deliberate demo emissions at each log level")
                .register(registry);
    }

    private Timer demoTimer(String outcome) {
        return Timer.builder("guide.demo.requests").tag("outcome", outcome)
                .description("Demo API requests, timed on completion by the request-logging filter")
                .publishPercentiles(0.95)
                .register(registry);
    }

    /** One curated meter: identity plus type-specific readings. */
    public record MeterRow(String name, String type, Map<String, String> tags, Map<String, Object> values) {
    }
}

package guide.data.obs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Objects;

/**
 * Scrapes the app's OWN prometheus exposition for the {@code guide_*} lines,
 * so the metrics summary can show the exact text {@code /actuator/prometheus}
 * serves — the same bytes a scraper would pull, filtered to this app's
 * meters. Absent registry (a slice, or a build without the prometheus
 * dependency) degrades to an empty excerpt, never a failure.
 */
public final class PrometheusExcerpt {

    private static final int MAX_LINES = 40;

    private final MeterRegistry registry;

    public PrometheusExcerpt(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** The guide_* exposition lines — what a scraper pulls, minus everyone else's meters. */
    public List<String> lines() {
        if (registry instanceof PrometheusMeterRegistry prometheus) {
            return filter(prometheus.scrape());
        }
        if (registry instanceof CompositeMeterRegistry composite) {
            for (MeterRegistry member : composite.getRegistries()) {
                if (member instanceof PrometheusMeterRegistry prometheus) {
                    return filter(prometheus.scrape());
                }
            }
        }
        return List.of();
    }

    private static List<String> filter(String scrape) {
        return scrape.lines()
                .filter(line -> line.startsWith("guide_"))
                .limit(MAX_LINES)
                .toList();
    }
}

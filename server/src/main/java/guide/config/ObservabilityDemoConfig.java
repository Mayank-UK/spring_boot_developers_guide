package guide.config;

import ch.qos.logback.classic.LoggerContext;
import guide.data.obs.LogRingAppender;
import guide.data.obs.PrometheusExcerpt;
import guide.data.obs.RecordingSpanReporter;
import guide.data.obs.RestClientTraceProbe;
import guide.domain.jobs.JobQueue;
import guide.domain.jobs.JobWorker;
import guide.domain.obs.EmitDemoLog;
import guide.domain.obs.LogRing;
import guide.domain.obs.ObsMetrics;
import guide.domain.obs.ReadLogRing;
import guide.domain.obs.RunTracedHop;
import guide.domain.obs.SpanTimeline;
import guide.domain.obs.TraceProbe;
import guide.domain.posts.CreatePost;
import guide.presentation.filter.RequestLoggingFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.tracing.Tracer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Topic 33 (observability) wiring — the demo's composition root, plus the
 * two always-on pieces the whole app shares:
 *
 * - {@link RequestLoggingFilter} registered AFTER the request-id filter
 *   (order +10 over its default 0), so its completion line's MDC fields
 *   (requestId, traceId) are already on the thread when it logs;
 * - the {@link SpanTimeline} bean is ALSO the tracing {@code SpanReporter}
 *   Boot's autoconfiguration collects into the reporter chain — one object,
 *   two roles: the tracing pipeline reports INTO it, the demo reads OUT of
 *   it;
 * - the LogRing is looked up FROM Logback's configuration (the appender
 *   logback-spring.xml declares), with a programmatic fallback for contexts
 *   that never processed that file (test slices) — the ring is never a
 *   SECOND logging pipeline, always a view of the first.
 *
 * The MeterRegistry fallback keeps this config importable in @WebMvcTest
 * slices, where the actuator's metrics autoconfiguration is absent: the
 * full app always has the autoconfigured registry, and a slice gets a
 * plain in-memory one — meters exist everywhere, they only EXPORT where
 * the runtime says so. (The same honesty as the optional-service fakes.)
 */
@Configuration
public class ObservabilityDemoConfig {

    /** Slice fallback only — the full app's autoconfigured registry always wins. */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry simpleMeterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @Bean
    ObsMetrics obsMetrics(MeterRegistry registry) {
        return new ObsMetrics(registry);
    }

    /**
     * The ring of record: the appender logback-spring.xml attaches, found by
     * name; if nothing processed that file (a slice), a standalone appender
     * is created and attached to the root logger so the viewer still works.
     */
    @Bean
    LogRing logRing() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var configured = root.getAppender("OBS_RING");
        if (configured instanceof LogRingAppender ring) {
            return ring;
        }
        LogRingAppender fallback = new LogRingAppender();
        fallback.setCapacity(LogRingAppender.DEFAULT_CAPACITY);
        fallback.setContext(context);
        fallback.start();
        root.addAppender(fallback);
        return fallback;
    }

    /**
     * Gauges for the two teaching rings — occupancy of the log ring and of
     * the span timeline. Bound EAGERLY (not left to the metrics
     * autoconfiguration) so slices that import this config see the gauges
     * too; registration is idempotent, so the full app binding the same
     * binder again is a no-op.
     */
    @Bean
    MeterBinder obsRingGauges(LogRing logRing, RecordingSpanReporter spans, MeterRegistry registry) {
        MeterBinder binder = metrics -> {
            Gauge.builder("guide.obs.ring.size", logRing, LogRing::size)
                    .description("Events currently held in the in-process log ring")
                    .register(metrics);
            Gauge.builder("guide.obs.spans.size", spans, SpanTimeline::size)
                    .description("Finished spans currently held in the in-process span timeline")
                    .register(metrics);
        };
        binder.bindTo(registry);
        return binder;
    }

    /**
     * The span reporter the tracing pipeline reports into AND the demo reads
     * out of — one object, two roles, ONE bean (implementing both
     * SpanReporter and the SpanTimeline port) so by-type resolution never
     * sees twins.
     */
    @Bean
    RecordingSpanReporter recordingSpanReporter() {
        return new RecordingSpanReporter();
    }

    @Bean
    ReadLogRing readLogRing(LogRing logRing) {
        return new ReadLogRing(logRing);
    }

    @Bean
    EmitDemoLog emitDemoLog(LogRing logRing, ObsMetrics metrics) {
        return new EmitDemoLog(logRing, metrics);
    }

    /** Scrapes the app's own exposition for the summary's prometheus excerpt. */
    @Bean
    PrometheusExcerpt prometheusExcerpt(MeterRegistry registry) {
        return new PrometheusExcerpt(registry);
    }

    /**
     * The hop's cross-layer work. Collaborators arrive as ObjectProviders on
     * purpose: slices import this config alone, and the hop reports absent
     * machinery as skipped steps instead of failing the context.
     */
    @Bean
    RunTracedHop runTracedHop(ObjectProvider<CreatePost> createPost, ObjectProvider<JobQueue> jobQueue,
            ObjectProvider<JobWorker> jobWorker, ObsMetrics metrics) {
        return new RunTracedHop(createPost.getIfAvailable(), jobQueue.getIfAvailable(),
                jobWorker.getIfAvailable(), metrics);
    }

    @Bean
    TraceProbe traceProbe(ObjectProvider<Tracer> tracer, ObjectProvider<RestClient.Builder> clientBuilder,
            ObjectProvider<WebServerApplicationContext> serverContext, RecordingSpanReporter spans,
            LogRing logRing) {
        return new RestClientTraceProbe(tracer, clientBuilder, serverContext, spans, logRing);
    }

    /**
     * The always-on completion line. Order +10: after the request-id filter
     * (default 0) and after the observation filter (negative order), so both
     * MDC keys are present when the line is written.
     */
    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter(ObsMetrics metrics) {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter(metrics));
        // +10 over the default 0 the plain request-id filter bean registers at
        registration.setOrder(10);
        return registration;
    }
}

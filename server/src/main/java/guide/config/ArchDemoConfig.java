package guide.config;

import guide.core.aop.InvocationLog;
import guide.core.middleware.PipelineTraceLog;
import guide.data.arch.ArchInstrumentationAdapter;
import guide.data.arch.ClasspathMapAdapter;
import guide.data.arch.RestClientInternalProbe;
import guide.domain.arch.BuildLayerMap;
import guide.domain.arch.CodebaseMapSource;
import guide.domain.arch.FlowInstrumentation;
import guide.domain.arch.InternalProbe;
import guide.domain.arch.ProbeInternalApi;
import guide.domain.arch.TraceRequestFlow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 32 (architecture) wiring — the arch demo's composition root, and a
 * working specimen of the section that teaches it: FOUR ports bound to
 * adapters and TWO use cases over them, every wire an explicit @Bean. The
 * two rings come from their own topics' configs (AopDemoConfig,
 * MiddlewareDemoConfig) — cross-config method injection is the composition
 * root talking to itself, the only place in the app where dependencies are
 * chosen.
 *
 * The tracer's shape is the dependency rule drawn in beans: the traced
 * ProbeInternalApi sits between TraceRequestFlow and the InternalProbe port
 * specifically so the aspect's record lands in the ring BEFORE the
 * orchestrator reads it back.
 */
@Configuration
public class ArchDemoConfig {

    /**
     * Port one: the real internal HTTP call. The client inside it is
     * adapter-private (see RestClientInternalProbe) — topic 12's
     * apiDocsRestClient stays the app's only by-type RestClient bean, so
     * no @SpringBootTest ever meets an autowiring ambiguity here.
     */
    @Bean
    InternalProbe internalProbe() {
        return new RestClientInternalProbe();
    }

    /** Port two: the two evidence rings, mapped into domain records. */
    @Bean
    FlowInstrumentation flowInstrumentation(InvocationLog invocationLog, PipelineTraceLog pipelineTraceLog) {
        return new ArchInstrumentationAdapter(invocationLog, pipelineTraceLog);
    }

    /** Port three: the computed layer map from the running classpath. */
    @Bean
    CodebaseMapSource codebaseMapSource() {
        return new ClasspathMapAdapter();
    }

    /** The traced use case the story exhibits — the aspect records this bean's calls. */
    @Bean
    ProbeInternalApi probeInternalApi(InternalProbe internalProbe) {
        return new ProbeInternalApi(internalProbe);
    }

    /** The tracer's orchestrator: probe, then read the evidence back. */
    @Bean
    TraceRequestFlow traceRequestFlow(ProbeInternalApi probeInternalApi,
            FlowInstrumentation flowInstrumentation) {
        return new TraceRequestFlow(probeInternalApi, flowInstrumentation);
    }

    /** The map: computed structure plus the exemption ledger. */
    @Bean
    BuildLayerMap buildLayerMap(CodebaseMapSource codebaseMapSource) {
        return new BuildLayerMap(codebaseMapSource);
    }
}

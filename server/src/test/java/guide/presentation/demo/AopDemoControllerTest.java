package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.aop.InvocationRecord;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.aop.ProxyFacts;
import guide.domain.aop.SelfInvocationExhibit;
import guide.domain.aop.ShowProxyKinds;
import guide.domain.aop.ShowSelfInvocationTrap;
import guide.domain.aop.ShowTraceEvidence;
import guide.domain.aop.TraceEvidenceExhibit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 06's three demo endpoints: the wire contracts the
 * client demos parse (proxy verdicts, trace evidence, the trap report) and
 * the Result → HTTP mapping through the real advice — a Failed Result must
 * leave as the typed error envelope, not a raw 500.
 */
@WebMvcTest(AopDemoController.class)
class AopDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShowProxyKinds showProxyKinds;

    @MockitoBean
    private ShowTraceEvidence showTraceEvidence;

    @MockitoBean
    private ShowSelfInvocationTrap showSelfInvocationTrap;

    @Test
    void proxyServesThePerBeanVerdictsWireShape() throws Exception {
        when(showProxyKinds.execute()).thenReturn(Result.success(List.of(
                new ProxyFacts(
                        "tracedSampleSteps",
                        "guide.domain.aop.TracedSampleSteps",
                        "guide.domain.aop.TracedSampleSteps$$SpringCGLIB$$0",
                        "guide.domain.aop.TracedSampleSteps",
                        true,
                        "CGLIB subclass proxy",
                        "no interface on the target — the subclass proxy is the only mechanism",
                        List.of("org.springframework.aop.SpringProxy", "org.springframework.aop.Advised")),
                new ProxyFacts(
                        "invocationTraceAspect",
                        "guide.core.aop.InvocationTraceAspect",
                        "guide.core.aop.InvocationTraceAspect",
                        "guide.core.aop.InvocationTraceAspect",
                        false,
                        "not proxied",
                        "no advice's pointcut matches this bean — the container hands "
                                + "out the plain instance, getClass() is the authored class",
                        List.of()))));

        mockMvc.perform(get("/api/demo/aop/proxy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].beanName").value("tracedSampleSteps"))
                .andExpect(jsonPath("$[0].runtimeClass")
                        .value("guide.domain.aop.TracedSampleSteps$$SpringCGLIB$$0"))
                .andExpect(jsonPath("$[0].targetClass").value("guide.domain.aop.TracedSampleSteps"))
                .andExpect(jsonPath("$[0].proxied").value(true))
                .andExpect(jsonPath("$[0].mechanism").value("CGLIB subclass proxy"))
                .andExpect(jsonPath("$[0].decisionRule").value(
                        "no interface on the target — the subclass proxy is the only mechanism"))
                .andExpect(jsonPath("$[0].interfaces[0]").value("org.springframework.aop.SpringProxy"))
                .andExpect(jsonPath("$[1].beanName").value("invocationTraceAspect"))
                .andExpect(jsonPath("$[1].proxied").value(false))
                .andExpect(jsonPath("$[1].mechanism").value("not proxied"))
                .andExpect(jsonPath("$[1].interfaces").isEmpty());
    }

    @Test
    void traceServesOkAndExceptionRecords() throws Exception {
        InvocationRecord ok = new InvocationRecord(
                "guide.domain.aop.TracedSampleSteps.succeedingStep", "(demo-seed)",
                "ok", 250L, "req-7");
        InvocationRecord failure = new InvocationRecord(
                "guide.domain.aop.TracedSampleSteps.failingStep", "()",
                "exception:IllegalStateException", 120L, "req-7");
        when(showTraceEvidence.execute()).thenReturn(Result.success(new TraceEvidenceExhibit(
                "processed[demo-seed]", "deliberate failure to demonstrate outcome=exception",
                ok, failure, List.of(ok, failure))));

        mockMvc.perform(get("/api/demo/aop/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepResult").value("processed[demo-seed]"))
                .andExpect(jsonPath("$.failureMessage")
                        .value("deliberate failure to demonstrate outcome=exception"))
                .andExpect(jsonPath("$.okRecord.method")
                        .value("guide.domain.aop.TracedSampleSteps.succeedingStep"))
                .andExpect(jsonPath("$.okRecord.argsSummary").value("(demo-seed)"))
                .andExpect(jsonPath("$.okRecord.outcome").value("ok"))
                .andExpect(jsonPath("$.okRecord.durationNanos").value(250))
                .andExpect(jsonPath("$.okRecord.requestId").value("req-7"))
                .andExpect(jsonPath("$.exceptionRecord.outcome")
                        .value("exception:IllegalStateException"))
                .andExpect(jsonPath("$.logTail.length()").value(2));
    }

    @Test
    void selfInvocationReportsTheTrapFromTheLog() throws Exception {
        InvocationRecord outer = new InvocationRecord(
                "guide.domain.teaching.TracedOrderWorkflow.placeOrder", "(book)",
                "ok", 340L, "req-9");
        when(showSelfInvocationTrap.execute()).thenReturn(Result.success(new SelfInvocationExhibit(
                "order[book] ← reserved:book", "placeOrder", "reserveStock",
                true, false, List.of(outer))));

        mockMvc.perform(get("/api/demo/aop/self-invocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderSummary").value("order[book] ← reserved:book"))
                .andExpect(jsonPath("$.outerMethod").value("placeOrder"))
                .andExpect(jsonPath("$.innerMethod").value("reserveStock"))
                .andExpect(jsonPath("$.outerRecorded").value(true))
                .andExpect(jsonPath("$.innerRecorded").value(false))
                .andExpect(jsonPath("$.exhibitLog.length()").value(1))
                .andExpect(jsonPath("$.exhibitLog[0].method")
                        .value("guide.domain.teaching.TracedOrderWorkflow.placeOrder"));
    }

    @Test
    void aFailedResultLeavesAsTheTypedEnvelope() throws Exception {
        when(showProxyKinds.execute()).thenReturn(Result.failed(Failure.of(FailureKind.INTERNAL,
                "introspection unavailable", Map.of("demo", "aop:proxy"))));

        mockMvc.perform(get("/api/demo/aop/proxy"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("introspection unavailable"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.demo").value("aop:proxy"));
    }

    @Test
    void aNotFoundResultLeavesAsTheTyped404Envelope() throws Exception {
        when(showSelfInvocationTrap.execute()).thenReturn(Result.failed(Failure.of(
                FailureKind.NOT_FOUND,
                "no invocation log attached to this runner",
                Map.of("demo", "aop:self-invocation"))));

        mockMvc.perform(get("/api/demo/aop/self-invocation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.message").value("no invocation log attached to this runner"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.demo").value("aop:self-invocation"));
    }
}

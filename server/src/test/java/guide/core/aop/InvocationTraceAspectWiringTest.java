package guide.core.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.aop.TracedSampleSteps;
import guide.domain.teaching.ExhibitSelfInvocation;
import guide.domain.teaching.TracedOrderWorkflow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * The aspect's behavior contract, proven through a real proxying context
 * (same mechanism the Boot app uses; proxyTargetClass=true mirrors Boot's
 * spring.aop.proxy-target-class default):
 * - the traced beans come out as CGLIB proxies, and the aspect itself does not
 * - one call → exactly one record, with method/args/outcome/duration
 * - exceptions are rethrown UNTOUCHED and still produce a record
 * - the self-invocation trap: the inner method runs but leaves NO record
 * - Object-inherited methods (toString) are excluded by the pointcut
 */
class InvocationTraceAspectWiringTest {

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Wiring {
        @Bean
        InvocationLog invocationLog() {
            return new InvocationLog(32);
        }

        @Bean
        InvocationTraceAspect invocationTraceAspect(InvocationLog invocationLog) {
            return new InvocationTraceAspect(invocationLog);
        }

        @Bean
        TracedSampleSteps tracedSampleSteps() {
            return new TracedSampleSteps();
        }

        @Bean
        ExhibitSelfInvocation tracedOrderWorkflow() {
            return new TracedOrderWorkflow();
        }
    }

    /**
     * The classic mode (proxyTargetClass=false): interface beans become JDK
     * proxies, interface-less beans still get CGLIB. Standalone (not
     * extending Wiring) so its own @EnableAspectJAutoProxy attributes are
     * the ones the registrar processes.
     */
    @Configuration
    @EnableAspectJAutoProxy
    static class ClassicWiring {
        @Bean
        InvocationLog classicInvocationLog() {
            return new InvocationLog(32);
        }

        @Bean
        InvocationTraceAspect classicInvocationTraceAspect(InvocationLog invocationLog) {
            return new InvocationTraceAspect(invocationLog);
        }

        @Bean
        TracedSampleSteps classicTracedSampleSteps() {
            return new TracedSampleSteps();
        }

        @Bean
        ExhibitSelfInvocation classicTracedOrderWorkflow() {
            return new TracedOrderWorkflow();
        }
    }

    private AnnotationConfigApplicationContext context;
    private InvocationLog log;

    @BeforeEach
    void startContext() {
        context = new AnnotationConfigApplicationContext(Wiring.class);
        log = context.getBean(InvocationLog.class);
    }

    @AfterEach
    void stopContext() {
        context.close();
    }

    private List<String> recordedMethods() {
        return log.snapshot().stream().map(InvocationRecord::method).toList();
    }

    @Test
    void tracedBeansAreCglibProxiesButTheAspectIsNot() {
        Object steps = context.getBean(TracedSampleSteps.class);
        Object workflow = context.getBean(ExhibitSelfInvocation.class);
        Object aspect = context.getBean("invocationTraceAspect");

        assertTrue(AopUtils.isAopProxy(steps), "steps must be proxied");
        assertTrue(AopUtils.isCglibProxy(steps));
        assertTrue(steps.getClass().getName().contains("$$"), "runtime class shows proxy markers");
        assertTrue(AopUtils.isAopProxy(workflow), "workflow must be proxied");
        assertTrue(AopUtils.isCglibProxy(workflow), "Boot-style proxyTargetClass forces CGLIB");
        assertFalse(AopUtils.isAopProxy(aspect), "the aspect itself is never intercepted");
    }

    @Test
    void oneSuccessfulCallFilesExactlyOneOkRecord() {
        TracedSampleSteps steps = context.getBean(TracedSampleSteps.class);
        int before = log.size();

        String result = steps.succeedingStep("Seed");

        assertEquals("processed[seed]", result);
        assertEquals(before + 1, log.size(), "exactly one record per call (proceed ran once)");
        InvocationRecord record = log.tail(1).getFirst();
        assertEquals("guide.domain.aop.TracedSampleSteps.succeedingStep", record.method());
        assertEquals("(Seed)", record.argsSummary());
        assertEquals(InvocationTraceAspect.OUTCOME_OK, record.outcome());
        assertTrue(record.durationNanos() >= 0, "duration is present and non-negative");
    }

    @Test
    void exceptionsAreRethrownUntouchedAndStillRecorded() {
        TracedSampleSteps steps = context.getBean(TracedSampleSteps.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                steps::failingStep);

        assertEquals("deliberate failure to demonstrate outcome=exception", thrown.getMessage());
        InvocationRecord record = log.tail(1).getFirst();
        assertEquals("guide.domain.aop.TracedSampleSteps.failingStep", record.method());
        assertEquals("exception:IllegalStateException", record.outcome());
        assertTrue(record.durationNanos() >= 0);
    }

    @Test
    void theSelfInvocationTrapIsRealOuterRecordedInnerNot() {
        ExhibitSelfInvocation workflow = context.getBean(ExhibitSelfInvocation.class);
        assertEquals(TracedOrderWorkflow.class, AopUtils.getTargetClass(workflow));

        String orderSummary = workflow.placeOrder("book");

        assertEquals("order[book] ← reserved:book", orderSummary, "the inner step demonstrably RAN");
        List<String> methods = recordedMethods();
        assertTrue(methodsContain(methods, ".placeOrder"), "outer call was intercepted");
        assertFalse(methodsContain(methods, ".reserveStock"),
                "the internal delegation bypassed the proxy — no record may exist");
    }

    @Test
    void callingTheInnerMethodFromOutsideISInterceptedTheControlCase() {
        ExhibitSelfInvocation workflow = context.getBean(ExhibitSelfInvocation.class);

        String reservation = workflow.reserveStock("pen");

        assertEquals("reserved:pen", reservation);
        assertTrue(methodsContain(recordedMethods(), ".reserveStock"),
                "the same method IS traced when reached through the proxy");
    }

    @Test
    void objectMethodsLikeToStringAreExcludedByThePointcut() {
        Object steps = context.getBean(TracedSampleSteps.class);
        int before = log.size();

        String rendered = steps.toString();

        assertFalse(rendered.isBlank());
        assertEquals(before, log.size(), "toString must leave no record");
        assertFalse(methodsContain(recordedMethods(), ".toString"));
    }

    @Test
    void classicModeChoosesJdkProxyForInterfaceBeansAndCglibForTheRest() {
        try (AnnotationConfigApplicationContext classic =
                new AnnotationConfigApplicationContext(ClassicWiring.class)) {
            ExhibitSelfInvocation workflow = classic.getBean(ExhibitSelfInvocation.class);
            Object steps = classic.getBean(TracedSampleSteps.class);
            InvocationLog classicLog = classic.getBean(InvocationLog.class);

            assertTrue(AopUtils.isJdkDynamicProxy(workflow),
                    "interface bean in proxyTargetClass=false mode is a JDK dynamic proxy");
            assertTrue(workflow.getClass().getName().contains("$Proxy"),
                    "JDK proxy classes carry the $Proxy marker");
            assertTrue(AopUtils.isCglibProxy(steps),
                    "an interface-less bean is CGLIB even in classic mode");

            classicLog.clear();
            String orderSummary = workflow.placeOrder("lamp");

            assertEquals("order[lamp] ← reserved:lamp", orderSummary);
            List<String> methods = classicLog.snapshot().stream()
                    .map(InvocationRecord::method).toList();
            assertTrue(methodsContain(methods, ".placeOrder"));
            assertFalse(methodsContain(methods, ".reserveStock"),
                    "the trap is mechanism-independent: same behavior under JDK proxies");
        }
    }

    private static boolean methodsContain(List<String> methods, String suffix) {
        return methods.stream().anyMatch(method -> method.endsWith(suffix));
    }
}

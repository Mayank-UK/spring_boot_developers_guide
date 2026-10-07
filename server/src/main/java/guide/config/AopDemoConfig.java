package guide.config;

import guide.core.aop.InvocationLog;
import guide.core.aop.InvocationTraceAspect;
import guide.data.aop.ProxyInspectionAdapter;
import guide.domain.aop.ProxyInspectionSource;
import guide.domain.aop.ShowProxyKinds;
import guide.domain.aop.ShowSelfInvocationTrap;
import guide.domain.aop.ShowTraceEvidence;
import guide.domain.aop.TracedSampleSteps;
import guide.domain.teaching.ExhibitSelfInvocation;
import guide.domain.teaching.TracedOrderWorkflow;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 06 (aop) demo wiring — the composition root for the guide's first
 * real aspect, same discipline as every other config class here: the aspect
 * is a plain class registered as an explicit @Bean (NOT @Component-scanned),
 * injected with its InvocationLog; the traced exhibits are beans like any
 * other; nothing below presentation knows Spring. With
 * spring-boot-starter-aspectj on the classpath, Boot's AOP autoconfiguration
 * turns the @Aspect bean into advisors and silently wraps every bean whose
 * methods match the pointcut — tracedSampleSteps and tracedOrderWorkflow
 * leave this class as CGLIB proxies, which is what the aop:proxy demo then
 * reports from the outside.
 */
@Configuration
public class AopDemoConfig {

    /** Bounded, thread-safe ring of recent invocations — the aspect's sink. */
    @Bean
    InvocationLog invocationLog() {
        return new InvocationLog(256);
    }

    /** The aspect itself: plain class + @Aspect, wired by constructor injection. */
    @Bean
    InvocationTraceAspect invocationTraceAspect(InvocationLog invocationLog) {
        return new InvocationTraceAspect(invocationLog);
    }

    // ---- the traced beans: these are what the container will proxy ----

    @Bean
    TracedSampleSteps tracedSampleSteps() {
        return new TracedSampleSteps();
    }

    /** Declared by its interface: injection points ask for ExhibitSelfInvocation. */
    @Bean
    ExhibitSelfInvocation tracedOrderWorkflow() {
        return new TracedOrderWorkflow();
    }

    // ---- the demo use cases ----

    @Bean
    ShowTraceEvidence showTraceEvidence(TracedSampleSteps steps, InvocationLog invocationLog) {
        return new ShowTraceEvidence(steps, invocationLog);
    }

    @Bean
    ShowSelfInvocationTrap showSelfInvocationTrap(ExhibitSelfInvocation exhibit,
            InvocationLog invocationLog) {
        return new ShowSelfInvocationTrap(exhibit, invocationLog);
    }

    @Bean
    ProxyInspectionSource proxyInspectionSource(ApplicationContext context) {
        return new ProxyInspectionAdapter(context);
    }

    @Bean
    ShowProxyKinds showProxyKinds(ProxyInspectionSource source) {
        return new ShowProxyKinds(source);
    }
}

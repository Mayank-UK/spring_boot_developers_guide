package guide.data.aop;

import guide.core.aop.InvocationTraceAspect;
import guide.domain.aop.ProxyFacts;
import guide.domain.aop.ProxyInspectionSource;
import guide.domain.aop.ShowTraceEvidence;
import guide.domain.aop.TracedSampleSteps;
import guide.domain.teaching.ExhibitSelfInvocation;
import java.util.Arrays;
import java.util.List;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;

/**
 * Adapter for topic 06's aop:proxy demo — the ONLY Spring-aware file in the
 * chain. Resolves each demo bean from the context by name (so the facts
 * describe the very object the container would inject) and classifies it
 * with Spring's own AopUtils: proxied or not, and if proxied, CGLIB
 * subclass or JDK dynamic proxy. The decision rule is spelled out per bean
 * so the wire answer teaches, not just reports.
 *
 * The JDK-vs-CGLIB rule this adapter encodes:
 * - no interface on the target → only CGLIB is possible
 * - interface present AND the classic proxy-target-class=false mode → JDK
 *   dynamic proxy over the interfaces
 * - interface present AND Boot's default (spring.aop.proxy-target-class=true,
 *   on since Boot 2.0) → CGLIB anyway — the subclass proxy keeps injection
 *   by concrete type working, at the cost of the subclass constraints
 *   (no final classes or methods)
 */
public class ProxyInspectionAdapter implements ProxyInspectionSource {

    /** A demo bean to report on: its bean name plus the type the wiring asked for. */
    record NamedBean(String beanName, Class<?> declaredType) {
    }

    private static final List<NamedBean> DEMO_BEANS = List.of(
            new NamedBean("tracedSampleSteps", TracedSampleSteps.class),
            new NamedBean("tracedOrderWorkflow", ExhibitSelfInvocation.class),
            new NamedBean("showTraceEvidence", ShowTraceEvidence.class),
            new NamedBean("invocationTraceAspect", InvocationTraceAspect.class));

    private final ApplicationContext context;

    public ProxyInspectionAdapter(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public List<ProxyFacts> wiredBeanProxyFacts() {
        return DEMO_BEANS.stream().map(this::factsFor).toList();
    }

    private ProxyFacts factsFor(NamedBean named) {
        Object bean = context.getBean(named.beanName());
        Class<?> runtimeClass = bean.getClass();
        boolean proxied = AopUtils.isAopProxy(bean);
        Class<?> targetClass = proxied ? AopUtils.getTargetClass(bean) : runtimeClass;
        String userInterface = firstUserInterface(targetClass);
        String mechanism;
        String decisionRule;
        if (!proxied) {
            mechanism = "not proxied";
            decisionRule = "no advice's pointcut matches this bean — the container hands "
                    + "out the plain instance, getClass() is the authored class";
        } else if (AopUtils.isCglibProxy(bean)) {
            mechanism = "CGLIB subclass proxy";
            decisionRule = userInterface == null
                    ? "no interface on the target — the subclass proxy is the only mechanism"
                    : "JDK-eligible (implements " + userInterface + ") but Boot's default "
                            + "spring.aop.proxy-target-class=true forces CGLIB";
        } else {
            mechanism = "JDK dynamic proxy";
            decisionRule = "interface-based proxy (proxy-target-class=false mode): implements "
                    + "the target's interfaces, delegating to the real object";
        }
        return new ProxyFacts(
                named.beanName(),
                named.declaredType().getName(),
                runtimeClass.getName(),
                targetClass.getName(),
                proxied,
                mechanism,
                decisionRule,
                interfaceNames(runtimeClass));
    }

    /** First interface on the target that is not a JDK type, or null when there is none. */
    private static String firstUserInterface(Class<?> targetClass) {
        return Arrays.stream(targetClass.getInterfaces())
                .filter(candidate -> !candidate.getName().startsWith("java."))
                .map(Class::getSimpleName)
                .findFirst()
                .orElse(null);
    }

    private static List<String> interfaceNames(Class<?> runtimeClass) {
        return Arrays.stream(runtimeClass.getInterfaces())
                .map(Class::getName)
                .toList();
    }
}

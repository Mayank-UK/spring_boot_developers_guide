package guide.domain.aop;

import java.util.List;

/**
 * The aop:proxy demo's per-bean verdict: what the container actually handed
 * out at an injection point. Pure data — every Spring-aware decision
 * (AopUtils checks, class names) is made on the data side; this record just
 * carries the answer:
 * - {@code beanName} — the @Bean method that defined it
 * - {@code declaredType} — the type the wiring asked for (interface or class)
 * - {@code runtimeClass} — getClass().getName() as injected; proxy markers
 *   like {@code $$SpringCGLIB$$} show up HERE
 * - {@code targetClass} — the class underneath the proxy (same as runtime
 *   when the bean is not proxied)
 * - {@code proxied} / {@code mechanism} — whether advice wraps it, and how
 * - {@code decisionRule} — the rule that picked that mechanism, stated
 * - {@code interfaces} — what the injected object implements (proxies grow
 *   framework marker interfaces like SpringProxy and Advised)
 */
public record ProxyFacts(
        String beanName,
        String declaredType,
        String runtimeClass,
        String targetClass,
        boolean proxied,
        String mechanism,
        String decisionRule,
        List<String> interfaces) {

    public ProxyFacts {
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
    }
}

package guide.data.core.container;

import guide.domain.core.container.BeanSnapshot;
import guide.domain.core.container.ContainerFacts;
import guide.domain.core.container.ContainerSnapshotSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Adapter (topic 05's demo): the one file in the chain that touches Spring's
 * own container API — the same data-layer role AppInfoAdapter plays for topic
 * 04, now over the bean factory instead of the environment. It walks the
 * context's bean definitions and hands the domain a record of plain data;
 * the dependency rule's data half (presentation → domain ← data) keeps every
 * Spring import above this line.
 *
 * Defensive by design: an introspection demo must never 500 because one bean
 * refused to resolve, so each bean is snapshotted independently and failures
 * skip that bean, never the request. Proxy detection is honest about its
 * cost: it calls getBean, which instantiates lazy and prototype beans as a
 * side effect — acceptable for a teaching endpoint, and worth KNOWING about
 * the API you are being shown.
 */
public final class BeansAdapter implements ContainerSnapshotSource {

    private final ConfigurableApplicationContext context;

    public BeansAdapter(ConfigurableApplicationContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public ContainerFacts snapshot() {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        List<BeanSnapshot> beans = new ArrayList<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            try {
                beans.add(snapshotOf(beanFactory, beanName));
            } catch (RuntimeException skipped) {
                // unresolvable bean definition — report what resolved
            }
        }
        return new ContainerFacts(List.copyOf(beans));
    }

    /**
     * One bean as plain data: its type, scope, factory method (null for
     * scanned components, the @Bean method's name for registered ones), the
     * bean names it depends on, and whether the INSTANCE the container would
     * hand out is a proxy — CGLIB classes carry $$ in the name, JDK interface
     * proxies $Proxy. Proxy detection is the one call that can instantiate
     * something, so it is the first thing dropped when a bean misbehaves.
     */
    private BeanSnapshot snapshotOf(ConfigurableListableBeanFactory beanFactory, String beanName) {
        Class<?> type = context.getType(beanName);
        String scope = beanFactory.getBeanDefinition(beanName).getScope();
        String factoryMethod = beanFactory.getBeanDefinition(beanName).getFactoryMethodName();
        String[] dependencies = beanFactory.getDependenciesForBean(beanName);
        return new BeanSnapshot(
                beanName,
                type != null ? type.getName() : "unknown",
                scope == null || scope.isBlank() ? "singleton" : scope,
                factoryMethod,
                List.of(dependencies == null ? new String[0] : dependencies),
                isProxied(beanName));
    }

    private boolean isProxied(String beanName) {
        try {
            String instanceClassName = context.getBean(beanName).getClass().getName();
            return instanceClassName.contains("$$") || instanceClassName.contains("$Proxy");
        } catch (RuntimeException notInspectable) {
            // scoped to a request we are not in, factory refusing, … — undetectable
            return false;
        }
    }
}

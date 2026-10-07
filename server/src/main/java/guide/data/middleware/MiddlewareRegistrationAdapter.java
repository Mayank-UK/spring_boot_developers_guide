package guide.data.middleware;

import guide.domain.middleware.MiddlewareRegistrationSource;
import guide.domain.middleware.RegisteredMiddleware;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;

/**
 * Adapter for topic 08's middleware:pipeline demo — the only Spring-aware
 * file in the chain. Answers "which servlet filters does the running
 * application register, in what order?" from the REAL bean factory:
 * explicit FilterRegistrationBean beans first (their own order, their own
 * URL patterns), then every plain Filter bean not already covered by a
 * registration — sorted by effective order.
 *
 * The effective order re-derives with the same precedence the servlet
 * context initializer uses when it sorts the chain: the Ordered interface
 * first, then an @Order annotation on the filter class, else
 * Ordered.LOWEST_PRECEDENCE — which is why an unordered filter like this
 * repo's own requestIdFilter shows up LAST, after every filter that
 * declares an order. That number is not an opinion: it is what the sort
 * sees.
 */
public class MiddlewareRegistrationAdapter implements MiddlewareRegistrationSource {

    private final ApplicationContext context;

    public MiddlewareRegistrationAdapter(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public List<RegisteredMiddleware> servletFilters() {
        Set<Filter> alreadyRegistered = new HashSet<>();
        List<RegisteredMiddleware> middleware = new ArrayList<>();

        for (Map.Entry<String, FilterRegistrationBean<?>> entry
                : registrationBeans().entrySet()) {
            FilterRegistrationBean<?> registration = entry.getValue();
            if (!registration.isEnabled()) {
                continue;
            }
            Filter target = registration.getFilter();
            alreadyRegistered.add(target);
            middleware.add(new RegisteredMiddleware(
                    entry.getKey(), registration.getOrder(), "FilterRegistrationBean"));
        }

        for (Map.Entry<String, Filter> entry : context.getBeansOfType(Filter.class).entrySet()) {
            Filter filter = entry.getValue();
            if (alreadyRegistered.contains(filter)) {
                continue;
            }
            middleware.add(new RegisteredMiddleware(
                    entry.getKey(), orderOf(filter), "@Bean filter (auto-registered)"));
        }

        middleware.sort(Comparator.comparingInt(RegisteredMiddleware::order));
        return List.copyOf(middleware);
    }

    @SuppressWarnings("unchecked")
    private Map<String, FilterRegistrationBean<?>> registrationBeans() {
        return (Map<String, FilterRegistrationBean<?>>) (Map<String, ?>) context
                .getBeansOfType(FilterRegistrationBean.class);
    }

    /** Ordered interface, then @Order on the class, else LOWEST_PRECEDENCE — the chain sort's rule. */
    private static int orderOf(Filter filter) {
        if (filter instanceof Ordered ordered) {
            return ordered.getOrder();
        }
        return OrderUtils.getOrder(filter.getClass(), Ordered.LOWEST_PRECEDENCE);
    }
}

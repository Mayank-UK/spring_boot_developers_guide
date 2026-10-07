package guide.domain.middleware;

import java.util.List;

/**
 * Port (domain-owned interface): "what servlet filters does the running
 * application register, in what order?" The data-layer adapter answers from
 * the real bean factory; the domain never learns how the answer was found.
 */
public interface MiddlewareRegistrationSource {

    /** The registered servlet filters, sorted by effective chain order. */
    List<RegisteredMiddleware> servletFilters();
}

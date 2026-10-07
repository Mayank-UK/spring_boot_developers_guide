package guide.domain.routing;

import java.util.List;

/**
 * Port (topic 07): where the route table comes from. Implemented by the data
 * layer's {@code RoutingTableAdapter}, which reads Spring's own
 * RequestMappingHandlerMapping — this interface keeps the domain explainer
 * pure and testable against any list of routes.
 */
public interface RouteTableSource {

    /** Every registered annotation mapping, one entry per pattern. */
    List<RouteEntry> routes();
}

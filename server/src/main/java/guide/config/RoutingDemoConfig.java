package guide.config;

import guide.data.routing.RoutingTableAdapter;
import guide.domain.routing.ExplainRouteMatch;
import guide.domain.routing.ListRoutes;
import guide.domain.routing.RouteTableExhibit;
import guide.domain.routing.RouteTableSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Demo wiring for topic 07 (routing): the route explorer's adapter, exhibit,
 * and use cases — plus the one functional-routing specimen so the at-scale
 * section quotes a REAL RouterFunction, not an invention. Note the injected
 * {@link RequestMappingHandlerMapping}: that bean is the DispatcherServlet's
 * annotation registry, created by Web MVC's own configuration; the adapter
 * only reads it. The @Qualifier is load-bearing — with actuator on the
 * classpath there are TWO beans assignable to this type (the actuator's
 * controllerEndpointHandlerMapping is the other), so by-type wiring alone
 * is ambiguous.
 */
@Configuration
public class RoutingDemoConfig {

    @Bean
    RoutingTableAdapter routingTableAdapter(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        return new RoutingTableAdapter(handlerMapping);
    }

    @Bean
    RouteTableExhibit routeTableExhibit() {
        return new RouteTableExhibit();
    }

    @Bean
    ListRoutes listRoutes(RouteTableSource source, RouteTableExhibit exhibit) {
        return new ListRoutes(source, exhibit);
    }

    @Bean
    ExplainRouteMatch explainRouteMatch(RouteTableSource source, RouteTableExhibit exhibit) {
        return new ExplainRouteMatch(source, exhibit);
    }

    /**
     * The functional specimen: the same route table idea, registered as a
     * lambda instead of an annotated method. Honest caveat demonstrated live:
     * RouterFunction routes live in a DIFFERENT handler mapping
     * (SimpleUrlHandlerMapping-style RouterFunctionMapping), so the explorer's
     * table — built from RequestMappingHandlerMapping — does not list it.
     */
    @Bean
    RouterFunction<ServerResponse> routingFunctionalSpecimen() {
        return RouterFunctions.route()
                .GET("/api/demo/routing/functional/ping",
                        request -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(new FunctionalPing("RouterFunction",
                                        "a lambda handler — no annotation, no controller bean,"
                                                + " and invisible to the annotation registry")))
                .build();
    }

    /** The functional route's response body. */
    record FunctionalPing(String style, String note) {
    }
}

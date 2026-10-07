package guide.data.routing;

import guide.domain.routing.RouteEntry;
import guide.domain.routing.RouteTableSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Adapter (topic 07's demo): the one file in the app that reads Spring's own
 * mapping registry. {@link RequestMappingHandlerMapping#getHandlerMethods()}
 * returns every annotation mapping registered at startup — pattern conditions
 * already combined from class- and method-level @RequestMapping — and this
 * adapter flattens each into plain {@link RouteEntry} rows for the domain.
 * The dependency rule's data half in action: Spring mapping types stop here.
 *
 * Defensive by contract: an introspection demo must not 500 because one
 * mapping refused to classify, so any entry that cannot be resolved is
 * skipped and the rest of the table is still served. (Note what this adapter
 * deliberately does NOT see: functional RouterFunction routes live in a
 * different handler mapping — the explorer's table is the annotation table.)
 */
public final class RoutingTableAdapter implements RouteTableSource {

    private final RequestMappingHandlerMapping handlerMapping;

    public RoutingTableAdapter(RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping");
    }

    @Override
    public List<RouteEntry> routes() {
        List<RouteEntry> entries = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> registered
                : handlerMapping.getHandlerMethods().entrySet()) {
            try {
                RequestMappingInfo info = registered.getKey();
                HandlerMethod handler = registered.getValue();
                List<String> methods = methodsOf(info);
                List<String> produces = mediaTypesOf(info.getProducesCondition().getProducibleMediaTypes());
                List<String> consumes = mediaTypesOf(info.getConsumesCondition().getConsumableMediaTypes());
                String controller = handler.getBeanType().getSimpleName();
                String bean = String.valueOf(handler.getBean());
                String handlerName = handler.getMethod().getName();
                for (String pattern : sorted(info.getPatternValues())) {
                    entries.add(new RouteEntry(pattern, methods, controller, bean,
                            handlerName, produces, consumes));
                }
            } catch (RuntimeException skipped) {
                // unresolvable mapping — an introspection table reports what it can read
            }
        }
        return List.copyOf(entries);
    }

    /** Empty set = the mapping declares no method and answers ANY. */
    private static List<String> methodsOf(RequestMappingInfo info) {
        List<String> methods = new ArrayList<>();
        for (RequestMethod method : info.getMethodsCondition().getMethods()) {
            methods.add(method.name());
        }
        return sorted(methods);
    }

    private static List<String> mediaTypesOf(java.util.Set<org.springframework.http.MediaType> mediaTypes) {
        List<String> rendered = new ArrayList<>();
        for (org.springframework.http.MediaType mediaType : mediaTypes) {
            rendered.add(mediaType.toString());
        }
        return sorted(rendered);
    }

    private static List<String> sorted(Iterable<String> values) {
        List<String> copy = new ArrayList<>();
        values.forEach(copy::add);
        copy.sort(String::compareTo);
        return List.copyOf(copy);
    }
}

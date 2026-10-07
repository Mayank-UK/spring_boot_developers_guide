package guide.domain.routing;

import java.util.List;
import java.util.Objects;

/**
 * One row of the application's route table: the mapping pattern as Spring
 * registered it (already combined from class- and method-level annotations),
 * the HTTP methods the mapping answers (empty list = ANY method), the
 * controller bean and handler method behind it, and the produces/consumes
 * declarations — everything the routing explorer shows, as plain data.
 *
 * This is the dependency-rule boundary object: the data adapter produces
 * these from Spring's own registry, and nothing past domain/ ever sees a
 * Spring mapping type.
 *
 * @param pattern   combined path pattern, e.g. {@code /api/content/{topicId}}
 * @param methods   sorted HTTP method names; empty means the mapping answers
 *                  every method (a bare {@code @RequestMapping})
 * @param controller controller class simple name, e.g. {@code ContentController}
 * @param bean      Spring bean name, e.g. {@code contentController}
 * @param handler   handler method name, e.g. {@code detail}
 * @param produces  declared producible media types (often empty = any)
 * @param consumes  declared consumable media types (often empty = any)
 */
public record RouteEntry(String pattern, List<String> methods, String controller, String bean,
        String handler, List<String> produces, List<String> consumes) {

    public RouteEntry {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(bean, "bean");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(produces, "produces");
        Objects.requireNonNull(consumes, "consumes");
        methods = List.copyOf(methods);
        produces = List.copyOf(produces);
        consumes = List.copyOf(consumes);
    }

    /** Convenience for the explainer: does this entry answer the given (uppercase) method? */
    public boolean allowsMethod(String method) {
        return methods.isEmpty() || methods.contains(method);
    }
}

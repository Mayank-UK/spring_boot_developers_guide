package guide.domain.middleware;

/**
 * One servlet filter the running context actually registers, as the demo's
 * introspection sees it: the bean name, the effective chain order (the
 * number the sort uses), and how it was registered — a plain {@code @Bean}
 * filter that Boot auto-registered, or an explicit FilterRegistrationBean.
 */
public record RegisteredMiddleware(String name, int order, String registration) {
}

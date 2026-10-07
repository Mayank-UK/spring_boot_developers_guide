package guide.domain.core.container;

/**
 * This repo's own convention: use cases are the beans of the domain layer —
 * classes under guide.domain. This app names them as verb phrases (ShowAppInfo,
 * ListTopicSummaries) rather than with a UseCase suffix, so the layer is the
 * reliable signal. Honest footnote the demo displays too: these convention
 * beans are themselves domain-layer beans, so they appear in their own list —
 * introspection reports what is there, including the introspectors.
 */
public final class LayeredUseCases implements UseCaseConvention {

    private static final String DOMAIN_PREFIX = "guide.domain.";

    @Override
    public String name() {
        return "domain-layer beans (guide.domain.*)";
    }

    @Override
    public boolean matches(String beanClassName) {
        return beanClassName != null && beanClassName.startsWith(DOMAIN_PREFIX);
    }
}

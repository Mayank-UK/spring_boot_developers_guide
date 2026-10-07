package guide.domain.core.container;

/**
 * The other convention: class names ending in UseCase — the spelling many
 * codebases prefer because it greps uniformly. No bean in this repo matches
 * it (we use verb phrases), which is exactly the point of collecting
 * conventions as a list: a convention with zero hits costs one bean and never
 * edits the exhibit. Together with LayeredUseCases it forms the demo's
 * config-registered pair of one interface.
 */
public final class SuffixedUseCases implements UseCaseConvention {

    private static final String SUFFIX = "UseCase";

    @Override
    public String name() {
        return "UseCase-suffixed classes (*UseCase)";
    }

    @Override
    public boolean matches(String beanClassName) {
        return beanClassName != null && beanClassName.endsWith(SUFFIX);
    }
}

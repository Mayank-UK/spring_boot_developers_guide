package guide.domain.core.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The container exhibit's derivation, pinned with hand-built facts and no
 * container at all — the point of keeping the domain Spring-free: totals,
 * package layout, convention matching (including the honest self-match of the
 * convention beans), and the deterministic sample pick with its fallbacks.
 */
class ContainerExhibitTest {

    private static BeanSnapshot bean(String name, String className, String... dependsOn) {
        return new BeanSnapshot(name, className, "singleton", null, List.of(dependsOn), false);
    }

    private static final List<UseCaseConvention> CONVENTIONS =
            List.of(new LayeredUseCases(), new SuffixedUseCases());

    @Test
    void derivesTotalsPackageLayoutAndUseCaseBeans() {
        ContainerFacts facts = new ContainerFacts(List.of(
                bean("mvcHandler", "org.springframework.web.servlet.HandlerMapping"),
                bean("contentRegistry", "guide.content.ContentRegistry"),
                bean("contentRepository", "guide.data.content.ContentRepositoryAdapter", "contentRegistry"),
                bean("showAppInfo", "guide.domain.spring.ShowAppInfo", "appInfoSource"),
                bean("listTopicSummaries", "guide.domain.content.ListTopicSummaries", "contentRepository"),
                bean("healthController", "guide.presentation.HealthController")));

        ContainerExhibit exhibit = ContainerExhibit.of(facts, CONVENTIONS);

        assertEquals(6, exhibit.totalBeans());
        assertEquals(List.of(
                new ContainerExhibit.PackageView("content", 1),
                new ContainerExhibit.PackageView("data", 1),
                new ContainerExhibit.PackageView("domain", 2),
                new ContainerExhibit.PackageView("presentation", 1)), exhibit.byPackage());
        // Both conventions applied: layer matches the three use cases; suffix
        // would claim a *UseCase class; result is sorted and distinct.
        assertEquals(List.of("listTopicSummaries", "showAppInfo"), exhibit.useCaseBeans());
        assertEquals(List.of("domain-layer beans (guide.domain.*)", "UseCase-suffixed classes (*UseCase)"),
                exhibit.useCaseConventions());
    }

    @Test
    void suffixConventionClaimsUseCaseSuffixedClassesAnywhere() {
        ContainerFacts facts = new ContainerFacts(List.of(
                bean("syncOrdersUseCase", "guide.app.SyncOrdersUseCase", "repository")));

        ContainerExhibit exhibit = ContainerExhibit.of(facts, CONVENTIONS);

        assertEquals(List.of("syncOrdersUseCase"), exhibit.useCaseBeans());
    }

    @Test
    void sampleBeanIsTheFirstAlphabeticalGuideBeanWithDependencies() {
        ContainerFacts facts = new ContainerFacts(List.of(
                bean("mvcHandler", "org.springframework.web.servlet.HandlerMapping", "other"),
                bean("contentRegistry", "guide.content.ContentRegistry"),
                bean("zzzAdapter", "guide.data.zzz.ZzzAdapter", "dep"),
                bean("appInfoSource", "guide.data.spring.AppInfoAdapter", "environment", "context")));

        ContainerExhibit exhibit = ContainerExhibit.of(facts, CONVENTIONS);

        assertEquals("appInfoSource", exhibit.sampleBean().name());
        assertEquals("guide.data.spring.AppInfoAdapter", exhibit.sampleBean().className());
        assertEquals(List.of("environment", "context"), exhibit.sampleBean().dependsOn());
    }

    @Test
    void sampleBeanFallsBackToAnyGuideBeanThenAnyBean() {
        ContainerFacts onlyGuide = new ContainerFacts(List.of(bean("registry", "guide.content.Registry")));
        assertEquals("registry", ContainerExhibit.of(onlyGuide, CONVENTIONS).sampleBean().name());

        ContainerFacts onlyFramework =
                new ContainerFacts(List.of(bean("mvcHandler", "org.springframework.WebMvc", "dep")));
        assertEquals("mvcHandler", ContainerExhibit.of(onlyFramework, CONVENTIONS).sampleBean().name());

        ContainerExhibit empty = ContainerExhibit.of(new ContainerFacts(List.of()), CONVENTIONS);
        assertEquals("(no beans)", empty.sampleBean().name());
        assertTrue(empty.byPackage().isEmpty());
        assertTrue(empty.useCaseBeans().isEmpty());
    }

    @Test
    void nullConventionsYieldNoClaimsButStillDeriveTheRest() {
        ContainerFacts facts = new ContainerFacts(List.of(
                bean("showAppInfo", "guide.domain.spring.ShowAppInfo", "appInfoSource")));

        ContainerExhibit exhibit = ContainerExhibit.of(facts, null);

        assertEquals(1, exhibit.totalBeans());
        assertTrue(exhibit.useCaseBeans().isEmpty());
        assertTrue(exhibit.useCaseConventions().isEmpty());
    }
}

package guide.domain.core.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The core:container use cases over a fixed fake source (the port contract
 * paying off): the overview succeeds with conventions applied, the detail
 * lookup succeeds for a known bean, and unknown/blank names leave as the
 * TYPED failures the controller maps onto the 404/400 envelopes.
 */
class ContainerUseCasesTest {

    private static final ContainerSnapshotSource SOURCE = () -> new ContainerFacts(List.of(
            new BeanSnapshot("showAppInfo", "guide.domain.spring.ShowAppInfo", "singleton",
                    "showAppInfo", List.of("appInfoSource"), false),
            new BeanSnapshot("mvcHandler", "org.springframework.web.HandlerMapping", "singleton",
                    null, List.of(), false)));

    @Test
    void showContainerSucceedsWithConventionsApplied() {
        ShowContainer showContainer =
                new ShowContainer(SOURCE, List.of(new LayeredUseCases(), new SuffixedUseCases()));

        Result<ContainerExhibit> result = showContainer.execute();

        assertInstanceOf(Success.class, result);
        ContainerExhibit exhibit = ((Success<ContainerExhibit>) result).value();
        assertEquals(2, exhibit.totalBeans());
        assertEquals(List.of("showAppInfo"), exhibit.useCaseBeans());
    }

    @Test
    void inspectBeanSucceedsForAKnownBean() {
        Result<BeanSnapshot> result = new InspectBean(SOURCE).execute("showAppInfo");

        assertInstanceOf(Success.class, result);
        BeanSnapshot snapshot = ((Success<BeanSnapshot>) result).value();
        assertEquals("showAppInfo", snapshot.name());
        assertEquals("guide.domain.spring.ShowAppInfo", snapshot.className());
        assertEquals("showAppInfo", snapshot.factoryMethod());
        assertEquals(List.of("appInfoSource"), snapshot.dependsOn());
    }

    @Test
    void unknownBeanLeavesAsTypedNotFound() {
        Result<BeanSnapshot> result = new InspectBean(SOURCE).execute("no-such-bean");

        assertInstanceOf(Failed.class, result);
        Failed<BeanSnapshot> failed = (Failed<BeanSnapshot>) result;
        assertEquals(FailureKind.NOT_FOUND, failed.failure().kind());
        assertEquals("no-such-bean", failed.failure().details().get("bean"));
        assertEquals("core:container", failed.failure().details().get("demo"));
        assertTrue(failed.failure().message().contains("no-such-bean"));
    }

    @Test
    void blankNameLeavesAsTypedValidation() {
        Result<BeanSnapshot> result = new InspectBean(SOURCE).execute("   ");

        assertInstanceOf(Failed.class, result);
        assertEquals(FailureKind.VALIDATION, ((Failed<BeanSnapshot>) result).failure().kind());
    }
}

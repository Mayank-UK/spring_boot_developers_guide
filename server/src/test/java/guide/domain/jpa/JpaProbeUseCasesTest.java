package guide.domain.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import guide.core.errors.AppException;
import guide.core.errors.ValidationException;
import guide.domain.posts.PostStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Use-case tests, no Spring: the validation shell around the port (bounds,
 * wire parsing, defaults) — the layer the @DataJpaTest slice deliberately
 * does not cover (repository tests are not use case tests).
 */
class JpaProbeUseCasesTest {

    private static final JpaProbePort STUB = new JpaProbePort() {
        @Override
        public ProbeReport probe(ProbeMode mode, int size) {
            return new ProbeReport(mode.wireName(), size, 0, 0, 0, 0L, "stub");
        }

        @Override
        public LockingReport lockRace() {
            return new LockingReport("id", "first", 1, true, "x", "409", "stub");
        }

        @Override
        public PageReport page(int number, int size, PostStatus status, boolean classProjection) {
            return new PageReport(number, size, 0, 0, true, true, 0, "stub", List.of());
        }
    };

    @Test
    void probeDefaultsSizeAndValidatesBoundsAndMode() {
        RunJpaProbe useCase = new RunJpaProbe(STUB);
        assertEquals(5, useCase.run("n-plus-1", null).size(), "null size defaults to 5");
        assertEquals("batch", useCase.run("batch", 12).mode(), "size 12 is the inclusive upper bound");

        assertThrows(ValidationException.class, () -> useCase.run("n-plus-1", 1), "size 1 is below the floor");
        assertThrows(ValidationException.class, () -> useCase.run("n-plus-1", 13), "size 13 is above the ceiling");
        assertThrows(ValidationException.class, () -> useCase.run("magic", 5), "unknown mode words are validation failures");
    }

    @Test
    void lockingParsesSurfaceAndPaginationParsesFilters() {
        RunLockingProbe locking = new RunLockingProbe(STUB);
        assertEquals("409", locking.run(null).surfacedAs(), "default surface is the report");
        assertThrows(ValidationException.class, () -> locking.run("bogus"));

        ReadJpaPage pagination = new ReadJpaPage(STUB);
        assertEquals(0, pagination.read(null, null, null, null).number(), "page defaults to 0");
        assertEquals(10, pagination.read(0, null, "draft", null).size(),
                "a valid status wire word passes through; size defaults to 10");
        assertEquals(0, pagination.read(0, 5, null, "class").number(), "the class projection word is accepted");
        assertThrows(ValidationException.class, () -> pagination.read(-1, 10, null, null));
        assertThrows(ValidationException.class, () -> pagination.read(0, 10, "archived", null));
        assertThrows(AppException.class, () -> pagination.read(0, 99, null, null));
    }
}

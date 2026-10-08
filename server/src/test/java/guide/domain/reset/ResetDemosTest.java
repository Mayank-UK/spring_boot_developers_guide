package guide.domain.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The sweep's rules as executables, no Spring: rows sorted by id whatever
 * the registration order; empty filter = every area; the filter dedupes
 * and trims; an unknown area is a validation failure that resets NOTHING
 * and quotes the DERIVED known list; one area's Failed Result (or an
 * unexpected throw) becomes a reset=false row while the rest of the sweep
 * still runs; duplicate registration ids are a wiring bug that fails
 * loudly at construction.
 */
class ResetDemosTest {

    /** An area that counts its resets — behavior pins need the call count. */
    private record CountingArea(String id, AtomicInteger resets) implements ResettableDemo {

        static CountingArea healthy(String id) {
            return new CountingArea(id, new AtomicInteger());
        }

        @Override
        public Result<String> reset() {
            resets.incrementAndGet();
            return Result.success("cleared " + id);
        }
    }

    @Test
    void rowsAreSortedByIdWhateverTheRegistrationOrder() {
        ResetDemos sweep = new ResetDemos(List.of(
                CountingArea.healthy("thirdparty"), CountingArea.healthy("jobs"),
                CountingArea.healthy("messaging")));

        assertEquals(List.of("jobs", "messaging", "thirdparty"), sweep.knownAreas());

        Result<ResetDemos.ResetReport> result = sweep.execute(null);
        ResetDemos.ResetReport report = assertSuccess(result);
        assertEquals(List.of("jobs", "messaging", "thirdparty"),
                report.reset().stream().map(ResetDemos.AreaReport::area).toList());
        assertTrue(report.reset().stream().allMatch(ResetDemos.AreaReport::reset));
    }

    @Test
    void anEmptyFilterMeansEveryArea() {
        ResetDemos sweep = new ResetDemos(List.of(
                CountingArea.healthy("jobs"), CountingArea.healthy("patterns")));
        assertEquals(2, assertSuccess(sweep.execute(List.of())).reset().size());
        assertEquals(2, assertSuccess(sweep.execute(null)).reset().size());
    }

    @Test
    void theFilterTrimsBlanksAndDedupes() {
        ResetDemos sweep = new ResetDemos(List.of(
                CountingArea.healthy("jobs"), CountingArea.healthy("messaging")));

        ResetDemos.ResetReport report =
                assertSuccess(sweep.execute(List.of(" messaging ", "", "messaging", "jobs")));

        assertEquals(List.of("jobs", "messaging"),
                report.reset().stream().map(ResetDemos.AreaReport::area).toList());
    }

    @Test
    void anUnknownAreaFailsValidationQuotesTheDerivedListAndResetsNothing() {
        CountingArea jobs = CountingArea.healthy("jobs");
        ResetDemos sweep = new ResetDemos(List.of(jobs, CountingArea.healthy("messaging")));

        Result<ResetDemos.ResetReport> result = sweep.execute(List.of("jobs", "nope"));
        assertInstanceOf(Failed.class, result);
        Failed<?> failed = (Failed<?>) result;

        assertEquals(FailureKind.VALIDATION, failed.failure().kind());
        assertTrue(failed.failure().message().contains("unknown area"),
                "message names the offense: " + failed.failure().message());
        assertTrue(failed.failure().message().contains("nope"),
                "message quotes the unknown area: " + failed.failure().message());
        assertTrue(failed.failure().message()
                        .contains("(known areas: jobs, messaging)"),
                "message carries the derived known list: " + failed.failure().message());
        assertEquals(0, jobs.resets().get(), "the typo sweep reset nothing");
    }

    @Test
    void aFailedAreaBecomesAFalseRowAndTheRestStillRun() {
        ResettableDemo broken = new ResettableDemo() {
            @Override
            public String id() {
                return "messaging";
            }

            @Override
            public Result<String> reset() {
                return Result.failed(Failure.of(FailureKind.UNAVAILABLE, "pg is gone"));
            }
        };
        CountingArea jobs = CountingArea.healthy("jobs");
        ResetDemos sweep = new ResetDemos(List.of(broken, jobs));

        ResetDemos.ResetReport report = assertSuccess(sweep.execute(null));

        // sorted by id: jobs first, the broken messaging second
        ResetDemos.AreaReport jobsRow = report.reset().get(0);
        assertTrue(jobsRow.reset());
        assertEquals(1, jobs.resets().get(), "the healthy area still reset");
        ResetDemos.AreaReport messagingRow = report.reset().get(1);
        assertFalse(messagingRow.reset());
        assertEquals("pg is gone", messagingRow.note());
    }

    @Test
    void aThrowingAreaCannotAbortTheSweep() {
        ResettableDemo explosive = new ResettableDemo() {
            @Override
            public String id() {
                return "messaging";
            }

            @Override
            public Result<String> reset() {
                throw new IllegalStateException("boom");
            }
        };
        CountingArea jobs = CountingArea.healthy("jobs");
        ResetDemos sweep = new ResetDemos(List.of(explosive, jobs));

        ResetDemos.ResetReport report = assertSuccess(sweep.execute(null));

        // sorted by id: jobs first, the explosive messaging second
        assertTrue(report.reset().get(0).reset());
        assertEquals(1, jobs.resets().get());
        ResetDemos.AreaReport messagingRow = report.reset().get(1);
        assertFalse(messagingRow.reset());
        assertTrue(messagingRow.note().contains("IllegalStateException"),
                "the note names the throw: " + messagingRow.note());
        assertTrue(messagingRow.note().contains("boom"),
                "the note carries the message: " + messagingRow.note());
    }

    @Test
    void duplicateRegistrationIdsFailLoudlyAtConstruction() {
        assertThrows(IllegalStateException.class,
                () -> new ResetDemos(List.of(CountingArea.healthy("jobs"),
                        CountingArea.healthy("jobs"))));
    }

    @Test
    void theFilterResetDoesNotTouchUnregisteredSiblingAreas() {
        CountingArea jobs = CountingArea.healthy("jobs");
        CountingArea patterns = CountingArea.healthy("patterns");
        ResetDemos sweep = new ResetDemos(List.of(jobs, patterns));

        ResetDemos.ResetReport report = assertSuccess(sweep.execute(List.of("patterns")));

        assertEquals(1, report.reset().size());
        assertEquals("patterns", report.reset().get(0).area());
        assertEquals(1, patterns.resets().get());
        assertEquals(0, jobs.resets().get(), "the unrequested area was not touched");
    }

    private static ResetDemos.ResetReport assertSuccess(Result<ResetDemos.ResetReport> result) {
        return switch (result) {
            case guide.core.result.Success<ResetDemos.ResetReport> success -> success.value();
            case Failed<ResetDemos.ResetReport> failed ->
                    throw new AssertionError("expected success, got " + failed.failure());
        };
    }
}

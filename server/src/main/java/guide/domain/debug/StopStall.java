package guide.domain.debug;

import guide.core.result.Result;

/**
 * Use case: stop the parked stall and answer the final snapshot. Stop is
 * deliberately IDEMPOTENT — stopping an idle theater is not an error, it is
 * the same state you wanted anyway (nothing parked) — so the answer carries
 * a note about how long the stall was parked instead of a failure. The
 * theater joins its threads before returning, so by the time the report
 * renders, nothing is left running.
 */
public final class StopStall {

    private final StallTheater theater;
    private final ReadStallThreads read;

    public StopStall(StallTheater theater, ReadStallThreads read) {
        this.theater = theater;
        this.read = read;
    }

    public Result<StallReport> execute() {
        theater.stop();
        return Result.success(read.execute());
    }
}

package guide.domain.reset;

import guide.core.result.Result;

/**
 * The port every resettable demo area implements so the SHARED reset
 * endpoint (POST /api/demo/reset) can return the guide to a clean slate —
 * guide infrastructure, not a curriculum topic: several demos mutate
 * server-side state across requests (the jobs console, the messaging
 * outbox/stream machinery, the saga world, …), a reader following multiple
 * tours accumulates that state, and before this port existed only some
 * areas owned a reset of their own.
 *
 * The contract is deliberately two-string small:
 * - {@link #id()} is the STABLE area key the {@code ?areas=} filter speaks
 *   and the report row carries (jobs, messaging, patterns, …) — the known
 *   list in the filter's validation message is DERIVED from the registered
 *   ids, never hardcoded anywhere else;
 * - {@link #reset()} returns the area's outcome as a value like every use
 *   case in this guide: success carries an optional human note (what was
 *   cleared), a Failed Result names the reason the area could not be reset
 *   (live infrastructure unreachable is the expected case). Implementations
 *   that adapt INFRASTRUCTURE (data/ adapters) translate their exceptions
 *   into Failed Results themselves so one area's outage cannot abort the
 *   whole sweep — a reset is a report, never a transaction.
 */
public interface ResettableDemo {

    /** The stable area id — lowercase, hyphen-free, one word per area. */
    String id();

    /**
     * Reset this area's demo state. Success value is an optional note for
     * the report row (null allowed); failures are VALUES, not throws.
     */
    Result<String> reset();
}

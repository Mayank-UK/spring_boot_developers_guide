package guide.domain.jpa;

import guide.domain.posts.PostStatus;

/**
 * The topic 18 probe port: what the JPA side can be asked to demonstrate,
 * stated in domain words. The adapter (guide.data.jpa.PgJpaProbe) implements
 * all three against real PostgreSQL and the JpaQueryCounter; use cases own
 * validation and never learn an EntityManager exists.
 *
 * The three report records live here, beside the port, because they are the
 * CONTRACT — presentation and topic tests shape their wire assertions on
 * them, and the adapter only fills them in.
 */
public interface JpaProbePort {

    /**
     * Runs one page of `size` specimen posts under one fetch shape and
     * reports the honest measurement: how many SQL statements Hibernate
     * issued for the READ phase, how many posts and related rows came back,
     * and how long the round trips took. Specimens are seeded and rolled
     * back inside one transaction — the probe leaves no residue.
     */
    ProbeReport probe(ProbeMode mode, int size);

    /**
     * The optimistic-locking story: one committed row, two readers that both
     * load it before either writes, first writer wins (version bumps), the
     * stale second writer is rejected by the {@code @Version} check and
     * surfaces as the guide's 409 conflict envelope when asked to.
     */
    LockingReport lockRace();

    /**
     * One real page with {@code Page} metadata (totalElements, totalPages,
     * hasNext) plus the count-query cost — how many SQL statements the paged
     * read cost. Interface-projection mode composes a Specification from the
     * optional status filter; class-projection mode runs a fixed
     * constructor-expression query (the asymmetry is the lesson).
     */
    PageReport page(int number, int size, PostStatus status, boolean classProjection);

    /** What one probe run measured — the numbers the N+1 section argues from. */
    record ProbeReport(
            String mode,
            int size,
            int queriesIssued,
            int postsReturned,
            int entriesLoaded,
            long wallTimeNanos,
            String verdict) {
    }

    /** How the two-writer race actually ended. */
    record LockingReport(
            String postId,
            String winner,
            int winningVersion,
            boolean staleWriterRejected,
            String rejectionType,
            String surfacedAs,
            String note) {
    }

    /** Page metadata plus one DTO row per page item — entities never leave the adapter. */
    record PageReport(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last,
            int queriesIssued,
            String projection,
            java.util.List<PageRow> rows) {
    }

    /** One read-shape row: what a projection returns instead of an entity. */
    record PageRow(String title, String status, int version) {
    }
}

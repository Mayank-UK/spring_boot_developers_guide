package guide.data.jpa;

import guide.domain.posts.PostStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The posts port's JPA-shaped sibling: ONE interface that Spring Data turns
 * into a working implementation at runtime — derived queries from method
 * names, JPQL from @Query, eager association loading from @EntityGraph, and
 * dynamic criteria from the JpaSpecificationExecutor supertype. No SQL
 * string lives here; no implementation class exists anywhere.
 *
 * The three specimen-page loaders return List (not Page) ON PURPOSE: a
 * Pageable count query would add one more SELECT to every mode and muddy the
 * counts the probe is measuring. Page metadata belongs to the pagination
 * endpoint, where it IS the lesson.
 */
public interface PostEntityRepository
        extends JpaRepository<PostEntity, UUID>, JpaSpecificationExecutor<PostEntity> {

    // ---- derived queries: the method name IS the query ----

    /** The probe's page key: LIKE 'prefix%' with a deterministic order — derived, zero annotations. */
    List<PostEntity> findByTitleStartingWith(String prefix, Sort sort);

    /** A derived aggregate — count(*) where status = ?, no JPQL needed. */
    long countByStatus(PostStatus status);

    /** The interface-projection specimen: same page as entities, but only the named properties leave the database. */
    Page<PostSummary> findByStatus(PostStatus status, Pageable pageable);

    /** The locking demo's lookup: newest published post, at most one row. */
    Optional<PostEntity> findFirstByStatusOrderByCreatedAtDesc(PostStatus status);

    // ---- @EntityGraph: eager-in-one-query, declared per query ----

    /** Same page as above, but the entries association loads in the SAME statement. */
    @EntityGraph(attributePaths = "entries")
    List<PostEntity> findWithEntriesByTitleStartingWith(String prefix, Sort sort);

    // ---- @Query JPQL: the statement in full view ----

    /** JOIN FETCH, the explicit one-query page: posts AND their entries in a single round trip. */
    @Query("select distinct p from PostEntity p left join fetch p.entries "
            + "where p.title like :prefix order by p.createdAt asc")
    List<PostEntity> findPageWithEntriesJoinFetch(@Param("prefix") String prefix);

    /** Constructor-expression projection, paged: reading titles WITHOUT loading entities. */
    @Query(value = "select new guide.data.jpa.PostTitleView(p.title, p.status) "
            + "from PostEntity p order by p.createdAt desc",
            countQuery = "select count(p) from PostEntity p")
    Page<PostTitleView> findTitleViewPage(Pageable pageable);
}

package guide.data.jpa;

import guide.domain.posts.PostStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specifications: composable WHERE predicates as values. A Specification is a
 * function from (root, query, builder) to a JPA Criteria Predicate — the
 * typesafe criteria tree under a functional interface. The pagination
 * endpoint composes hasStatus (when a filter arrives) with an "everything"
 * spec (when none does), and the composition is ordinary .and() — dynamic
 * filters without dynamic SQL strings, the exact problem
 * PgPostRepository solved with a StringBuilder of fixed fragments.
 */
public final class PostSpecifications {

    private PostSpecifications() {
    }

    /**
     * The neutral element: a predicate that constrains nothing. Spring Data
     * 4 overloads Specification.where(null) into ambiguity, so the neutral
     * spec states itself the way the criteria API spells "TRUE": a
     * conjunction of zero terms.
     */
    public static Specification<PostEntity> all() {
        return (root, query, builder) -> builder.conjunction();
    }

    /** status = ? — an equality the criteria builder types for you. */
    public static Specification<PostEntity> hasStatus(PostStatus status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }
}

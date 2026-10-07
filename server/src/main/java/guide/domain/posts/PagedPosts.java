package guide.domain.posts;

import java.util.List;

/**
 * One page of a paginated posts listing. The metadata contract for list
 * endpoints: which page this is (0-based), the requested size, and the totals
 * a client needs to render "page 2 of 7". totalPages is DERIVED at
 * construction (ceil(totalElements / size)) so it can never disagree with the
 * numbers that produced it — computed metadata is not negotiable state.
 */
public record PagedPosts(List<Post> items, int page, int size, long totalElements, int totalPages) {

    public PagedPosts {
        items = List.copyOf(items);
        if (page < 0) {
            throw new IllegalArgumentException("page is 0-based and cannot be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("totals cannot be negative");
        }
    }

    /** Derives totalPages from the totals — the only way callers should build a page. */
    public static PagedPosts of(List<Post> pageItems, int page, int size, long totalElements) {
        return new PagedPosts(pageItems, page, size, totalElements,
                (int) Math.ceil((double) totalElements / (double) size));
    }

    /** True when this page is the last one (an empty result set has no pages — its "last" is page 0). */
    public boolean isLast() {
        return page + 1 >= totalPages;
    }
}

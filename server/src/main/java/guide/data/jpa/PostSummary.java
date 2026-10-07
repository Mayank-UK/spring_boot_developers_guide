package guide.data.jpa;

import guide.domain.posts.PostStatus;

/**
 * The INTERFACE projection: Spring Data proxies this at runtime and fills it
 * from the query's selected columns — no entity is instantiated, no
 * persistence context entry exists, nothing can be dirty-checked. getTitle()
 * maps to the title column because the property name matches; that name
 * matching is the entire contract. This is the read shape the pagination
 * endpoint serves: reading rows without paying entity freight.
 */
public interface PostSummary {

    String getTitle();

    PostStatus getStatus();

    int getVersion();
}

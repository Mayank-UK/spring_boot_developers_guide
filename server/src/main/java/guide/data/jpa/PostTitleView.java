package guide.data.jpa;

import guide.domain.posts.PostStatus;

/**
 * The CLASS projection: a plain record the JPQL CONSTRUCTOR EXPRESSION fills
 * — "select new guide.data.jpa.PostTitleView(p.title, p.status)". Unlike the
 * interface projection this one is type-checked at build time (the query
 * names the constructor, so a signature drift fails compilation of the query
 * validation at startup), and it can carry derived values, not just column
 * echoes. It lives in data/jpa with the entity because it is a persistence
 * read shape, not a domain concept.
 */
public record PostTitleView(String title, PostStatus status) {
}

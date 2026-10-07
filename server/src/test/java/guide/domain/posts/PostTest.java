package guide.domain.posts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Post entity contract: the compact constructor re-enforces the content rules
 * (defense in depth — an internal caller cannot build an invalid post), the
 * lifecycle invariants hold (updatedAt ≥ createdAt, version ≥ 1), and the two
 * factories are the ONLY places version arithmetic happens: 1 at birth, +1
 * per revision, createdAt forever fixed.
 */
class PostTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final PostDraft DRAFT =
            new PostDraft("A title", "A body", java.util.List.of("t"), PostStatus.PUBLISHED);

    @Test
    void initialPostIsVersionOneWithEqualTimestamps() {
        Post post = Post.initial("id-1", DRAFT, NOW);

        assertEquals(1, post.version());
        assertEquals(NOW, post.createdAt());
        assertEquals(NOW, post.updatedAt());
        assertEquals(PostStatus.PUBLISHED, post.status());
    }

    @Test
    void revisionKeepsIdentityAndCreatedAtAndGrowsTheVersion() {
        Post post = Post.initial("id-1", DRAFT, NOW);
        Post revised = post.revised(
                new PostDraft("New title", "New body", java.util.List.of(), PostStatus.DRAFT),
                NOW.plusSeconds(3600));

        assertEquals("id-1", revised.id());
        assertEquals(2, revised.version());
        assertEquals(post.createdAt(), revised.createdAt(), "createdAt is fixed at birth");
        assertTrue(revised.updatedAt().isAfter(revised.createdAt()));
        assertEquals("New title", revised.title());
    }

    @Test
    void compactConstructorRefusesInvalidStateNoMatterWhoCalls() {
        assertThrows(IllegalArgumentException.class,
                () -> new Post("  ", "t", "b", null, PostStatus.DRAFT, 1, NOW, NOW),
                "a blank id cannot exist");
        assertThrows(IllegalArgumentException.class,
                () -> new Post("id", "x".repeat(121), "b", null, PostStatus.DRAFT, 1, NOW, NOW),
                "the entity re-enforces the title rule");
        assertThrows(IllegalArgumentException.class,
                () -> new Post("id", "t", "b", null, PostStatus.DRAFT, 0, NOW, NOW),
                "version starts at 1");
        assertThrows(IllegalArgumentException.class,
                () -> new Post("id", "t", "b", null, PostStatus.DRAFT, 1,
                        NOW, NOW.minusSeconds(1)),
                "updatedAt cannot precede createdAt");
        assertThrows(NullPointerException.class,
                () -> new Post("id", "t", "b", null, null, 1, NOW, NOW),
                "status is required");
    }
}

package guide.domain.posts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A post — the resource this half of the guide is built around. An immutable
 * record: identity (id), content judged at birth (title, body, tags), lifecycle
 * (status), optimistic-concurrency token (version), and history anchors
 * (createdAt, updatedAt).
 *
 * The compact constructor re-enforces the same content rules as PostDraft —
 * defense in depth: a use case, a batch job, or a future adapter that builds a
 * Post directly cannot construct an invalid one, no matter which door it uses.
 * Version arithmetic (1 at birth, +1 per revision) lives ONLY in the two
 * factories below, so the invariant "version counts revisions" has exactly one
 * keeper.
 */
public record Post(String id, String title, String body, List<String> tags, PostStatus status,
        long version, Instant createdAt, Instant updatedAt) {

    public Post {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("post id must be a non-blank string");
        }
        if (PostDraft.titleProblem(title) != null) {
            throw new IllegalArgumentException("post title " + PostDraft.titleProblem(title));
        }
        if (PostDraft.bodyProblem(body) != null) {
            throw new IllegalArgumentException("post body " + PostDraft.bodyProblem(body));
        }
        Objects.requireNonNull(status, "status");
        tags = PostDraft.normalizeTags(tags);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("post updatedAt cannot precede createdAt");
        }
        if (version < 1) {
            throw new IllegalArgumentException("post version starts at 1 and only grows");
        }
        title = title.strip();
        body = body.strip();
    }

    /** A post at birth: version 1, createdAt == updatedAt, content from the draft. */
    public static Post initial(String id, PostDraft draft, Instant now) {
        Objects.requireNonNull(draft, "draft");
        return new Post(id, draft.title(), draft.body(), draft.tags(), draft.status(), 1, now, now);
    }

    /** The next revision of this post: same identity and createdAt, content replaced, version +1. */
    public Post revised(PostDraft draft, Instant now) {
        Objects.requireNonNull(draft, "draft");
        return new Post(id, draft.title(), draft.body(), draft.tags(), draft.status(),
                version + 1, createdAt, now);
    }
}

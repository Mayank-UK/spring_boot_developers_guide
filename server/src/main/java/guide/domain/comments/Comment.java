package guide.domain.comments;

/**
 * A reader's comment on a post — the associated data the GraphQL topic loads
 * through the posts graph (Post.comments). Immutable record with the three
 * things a comment needs: identity, parent, and what was said (plus who said
 * it). Deliberately smaller than Post: it exists to make ASSOCIATION LOADING
 * interesting (one comment is trivial; N posts x their comments is the N+1
 * problem this topic teaches), not to be a full commenting domain — there is
 * no write side, no moderation state, no threading.
 */
public record Comment(String id, String postId, String author, String body) {

    public Comment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("comment id must be a non-blank string");
        }
        if (postId == null || postId.isBlank()) {
            throw new IllegalArgumentException("comment postId must be a non-blank string");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("comment author must be a non-blank string");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("comment body must be a non-blank string");
        }
        author = author.strip();
        body = body.strip();
    }
}

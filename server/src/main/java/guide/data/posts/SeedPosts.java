package guide.data.posts;

import guide.domain.posts.Post;
import guide.domain.posts.PostStatus;
import java.time.Instant;
import java.util.List;

/**
 * The deterministic seed behind the posts API: twelve posts with HARDCODED
 * UUID-stable ids and hardcoded timestamps. Determinism is the point — tests
 * address seed posts by id and assert list order, so the seed must be
 * byte-identical on every boot, in every container. (Phase 6's PostgreSQL
 * adapter replays this same list once, as a Flyway seed migration — the ids
 * survive the storage swap, which is why they are stable rather than minted.)
 *
 * Staggered createdAt (one per September 2026 day), newest first in this list;
 * two posts carry later updatedAt values so ordering by updatedAt differs
 * from createdAt, and two carry version > 1 to look honestly revised.
 */
public final class SeedPosts {

    private SeedPosts() {
    }

    /** The twelve seed posts, newest first (the API's default listing order). */
    public static List<Post> list() {
        return List.of(
                post("9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01",
                        "The uniform interface is the contract that scales",
                        "Six constraints make an API RESTful, but only one of them earns its keep every day: "
                                + "the uniform interface. Verbs with fixed meanings, status codes with fixed "
                                + "semantics, and one error shape everywhere — that is the contract that lets a "
                                + "client written against one endpoint survive ten years of server refactors.",
                        tags("rest", "api-design"), PostStatus.PUBLISHED, 1,
                        "2026-09-12T09:00:00Z", "2026-09-12T09:00:00Z"),
                post("8e0b4941-2c6d-4e79-8a10-5b3c7d1b6e02",
                        "Nouns, not verbs: designing the resource first",
                        "Every URL design mistake traces to one root: thinking in procedures instead of "
                                + "documents. /users/42/posts names a thing you can point at, link to, cache; "
                                + "/getUserPosts?userId=42&op=fetch names a procedure wearing a URL costume.",
                        tags("rest", "urls"), PostStatus.PUBLISHED, 1,
                        "2026-09-11T10:30:00Z", "2026-09-11T10:30:00Z"),
                post("7d2a3830-1b5c-4d68-790f-4a2b6c0a5d03",
                        "201, 202, 204: status codes are the contract's vocabulary",
                        "The status line is the first byte of documentation a client reads. 201 with a "
                                + "Location header says the resource now exists and where; 202 says accepted, "
                                + "not done — the honest answer for anything that becomes a background job; "
                                + "204 says done, deliberately bodyless.",
                        tags("http", "api-design"), PostStatus.PUBLISHED, 1,
                        "2026-09-10T14:15:00Z", "2026-09-10T14:15:00Z"),
                post("6c392729-0a4b-4c57-681e-391a5b094c04",
                        "Offset pagination is a promise you cannot keep forever",
                        "Offset paging is simple, index-friendly, and quietly dishonest at the edges: rows "
                                + "inserted between your page fetches make page three show what page two showed. "
                                + "Cursors (WHERE created_at < $last) cost more to build and buy stable pages. "
                                + "This API teaches offset honestly — and says which guarantee it makes.",
                        tags("pagination"), PostStatus.PUBLISHED, 1,
                        "2026-09-09T08:45:00Z", "2026-09-09T08:45:00Z"),
                post("5b481618-f93a-4b46-572d-280949083b05",
                        "One error envelope beats five correct ones",
                        "RFC 9457 problem documents are the standardized shape, and Spring can serve them. "
                                + "We still ship this guide's single envelope on every failure — code, message, "
                                + "requestId — because the property that matters to a client is not standards "
                                + "pedigree but predictability: one parser, every endpoint, every failure.",
                        tags("errors", "http"), PostStatus.PUBLISHED, 2,
                        "2026-09-08T11:20:00Z", "2026-09-10T16:40:00Z"),
                post("4a570507-e829-4a35-463c-17283a7272a0",
                        "Optimistic concurrency: the version field is the lock",
                        "Two editors, one post, no coordinator: the classic lost-update race. Optimistic "
                                + "concurrency dissolves it — every representation carries its version, every "
                                + "write must quote the version it read, and the second writer gets a 409 that "
                                + "names the current version. No locks held across requests.",
                        tags("concurrency", "api-design"), PostStatus.PUBLISHED, 1,
                        "2026-09-07T13:00:00Z", "2026-09-07T13:00:00Z"),
                post("39660406-d718-4924-354b-063729616107",
                        "Route versioning is boring, and boring survives",
                        "v2 adds a field. That is the whole migration story for most APIs: additive changes "
                                + "need no version at all, and when a break is unavoidable, /v2/ beside /v1/ "
                                + "is debuggable with curl — you can see which version a client uses from the "
                                + "access log alone. Header versioning hides the same fact where only your "
                                + "code can find it.",
                        tags("versioning"), PostStatus.PUBLISHED, 1,
                        "2026-09-06T15:30:00Z", "2026-09-06T15:30:00Z"),
                post("28753305-c607-3813-2459-950628505008",
                        "The URL is the API: query strings as contract surface",
                        "?page=0&size=20&sort=title,asc&status=published&tag=rest — every token in that "
                                + "string is contract. Sort syntax must be validated like a body field, filter "
                                + "names must be spelled the same across every collection, and defaults must "
                                + "be documented because clients will rely on them within the hour.",
                        tags("urls", "pagination"), PostStatus.PUBLISHED, 1,
                        "2026-09-05T09:45:00Z", "2026-09-05T09:45:00Z"),
                post("17842204-b506-2702-1347-841527394109",
                        "Drafts first: why creation defaults to invisible",
                        "A create that publishes immediately is a footgun aimed at your own feed. This "
                                + "resource's creation path defaults to draft status — the safe state — and "
                                + "publishing is a separate, deliberate transition. Defaults are API design: "
                                + "they are the behavior clients get when they are not looking.",
                        tags("api-design"), PostStatus.DRAFT, 1,
                        "2026-09-04T10:00:00Z", "2026-09-04T10:00:00Z"),
                post("06931103-a405-1601-0236-732426283210",
                        "Idempotency: the property that makes retries safe",
                        "Networks retry. A PUT replayed must leave the resource in the state the first PUT "
                                + "produced; a DELETE replayed must not resurrect anything. Unsafe verbs that "
                                + "are not idempotent (POST) are exactly where idempotency KEYS earn their "
                                + "complexity — but only once retries actually happen.",
                        tags("http", "reliability"), PostStatus.PUBLISHED, 3,
                        "2026-09-03T12:10:00Z", "2026-09-08T17:55:00Z"),
                post("fc120202-9304-0500-9125-623325172311",
                        "Thin controllers are an API design rule, not just architecture",
                        "When a controller parses, validates, calls one use case, and maps the Result to a "
                                + "status, the HTTP surface stays honest: every behavior is testable behind the "
                                + "port, and versioning becomes a mapping concern instead of a rewrite.",
                        tags("architecture", "api-design"), PostStatus.PUBLISHED, 1,
                        "2026-09-02T16:25:00Z", "2026-09-02T16:25:00Z"),
                post("eb010101-8203-9400-8014-514224061312",
                        "Teach the pipeline, not the trivia",
                        "A request's journey — filter chain, routing, binding, validation, use case, Result, "
                                + "status + envelope — is the one diagram that explains every endpoint this "
                                + "guide serves. Learn it once and every new endpoint is a variation you can "
                                + "read off the route table.",
                        tags("meta", "http"), PostStatus.DRAFT, 1,
                        "2026-09-01T08:00:00Z", "2026-09-01T08:00:00Z"));
    }

    private static Post post(String id, String title, String body, List<String> tags,
            PostStatus status, long version, String createdAt, String updatedAt) {
        return new Post(id, title, body, tags, status, version,
                Instant.parse(createdAt), Instant.parse(updatedAt));
    }

    private static List<String> tags(String... values) {
        return List.of(values);
    }
}

package guide.data.comments;

import guide.domain.comments.Comment;
import java.util.List;

/**
 * The deterministic seed behind the comments association: 27 comments across
 * the twelve seed posts (two each; three on the first uniform-interface post,
 * the error-envelope post, and the idempotency post). Hardcoded ids
 * (c-0001…c-0027), hardcoded order (oldest first within a post), hardcoded
 * everything — same contract as SeedPosts: byte-identical on every boot so
 * tests can assert order and demos can quote counts without flinching.
 *
 * The bodies are written as if real readers left them, each one on-topic for
 * its post — the N+1 section's example output should read like a comment
 * thread, not like fixture noise.
 */
final class SeedComments {

    private SeedComments() {
    }

    /** The 27 seed comments, ordered by post (newest post first, matching SeedPosts) then oldest comment first. */
    static List<Comment> list() {
        return List.of(
                comment("c-0001", "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01", "Mira",
                        "This reframed HATEOAS for me — the point was never hypermedia purism, it was \"fixed meanings I can refactor behind\"."),
                comment("c-0002", "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01", "Tobias",
                        "Ten years of server refactors is exactly our experience: clients keyed on verbs and status codes survived three storage rewrites."),
                comment("c-0003", "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01", "Priya",
                        "Would add: the uniform interface is also what lets proxies and gateways help you. They can't read your bespoke semantics."),
                comment("c-0004", "8e0b4941-2c6d-4e79-8a10-5b3c7d1b6e02", "Devon",
                        "\"A procedure wearing a URL costume\" is going straight into our API review checklist."),
                comment("c-0005", "8e0b4941-2c6d-4e79-8a10-5b3c7d1b6e02", "Lena",
                        "The pointable/testable distinction matters more than people think — we cache by URL noun and it just works."),
                comment("c-0006", "7d2a3830-1b5c-4d68-790f-4a2b6c0a5d03", "Sam",
                        "202 is criminally underused. Everything that becomes a job should return it, and half our incident reports trace to pretending work was synchronous."),
                comment("c-0007", "7d2a3830-1b5c-4d68-790f-4a2b6c0a5d03", "Mira",
                        "First byte of documentation — accurate. New joiners learn our API's meaning from status lines before they read a word of docs."),
                comment("c-0008", "6c392729-0a4b-4c57-681e-391a5b094c04", "Tobias",
                        "Lived the offset lie: scheduled reports paginating an inserting table showed duplicates across pages for months before anyone noticed."),
                comment("c-0009", "6c392729-0a4b-4c57-681e-391a5b094c04", "Priya",
                        "Cursors cost a day to build and pay for themselves the first time a client complains about \"missing\" rows. Cheap insurance."),
                comment("c-0010", "5b481618-f93a-4b46-572d-280949083b05", "Lena",
                        "One parser, every endpoint, every failure — this is the single highest-leverage decision in our client codebase."),
                comment("c-0011", "5b481618-f93a-4b46-572d-280949083b05", "Devon",
                        "We adopted RFC 9457 after a standards audit and kept the envelope keys identical. Predictability beat pedigree, agreed."),
                comment("c-0012", "5b481618-f93a-4b46-572d-280949083b05", "Sam",
                        "The requestId in the envelope is what makes support tickets actionable. Never ship an error shape without one."),
                comment("c-0013", "4a570507-e829-4a35-463c-17283a7272a0", "Mira",
                        "Lost-update war story: two support agents, one customer record, silent overwrite. Version fields since that day."),
                comment("c-0014", "4a570507-e829-4a35-463c-17283a7272a0", "Tobias",
                        "\"The version field is the lock\" — best one-line summary of optimistic concurrency I've read."),
                comment("c-0015", "39660406-d718-4924-354b-063729616107", "Priya",
                        "Additive changes need no version at all — this took our team two years and one painful /v1/search rewrite to internalize."),
                comment("c-0016", "39660406-d718-4924-354b-063729616107", "Lena",
                        "Header versioning hiding facts from the access log cost us a full morning once. Boring survives, confirmed."),
                comment("c-0017", "28753305-c607-3813-2459-950628505008", "Devon",
                        "Sort syntax validated like a body field — yes. Our first API accepted any ?sort= string and silently ignored the garbage."),
                comment("c-0018", "28753305-c607-3813-2459-950628505008", "Sam",
                        "The defaults-are-relied-upon-within-the-hour bit is painfully true. Document them like contract, because they are."),
                comment("c-0019", "17842204-b506-2702-1347-841527394109", "Mira",
                        "Draft-first saved us from an accidental publish exactly once, which paid for the extra state forever."),
                comment("c-0020", "17842204-b506-2702-1347-841527394109", "Priya",
                        "\"Defaults are the behavior clients get when they are not looking\" — frame it exactly like this in your API review."),
                comment("c-0021", "06931103-a405-1601-0236-732426283210", "Tobias",
                        "Idempotency keys only once retries actually happen — wise sequencing. Measure the retry rate before buying the complexity."),
                comment("c-0022", "06931103-a405-1601-0236-732426283210", "Lena",
                        "Mobile clients on flaky networks made us believers: PUT/DELETE idempotency is free, POST keys earned their keep."),
                comment("c-0023", "06931103-a405-1601-0236-732426283210", "Devon",
                        "The retry-safe trio in one post — verbs, replays, and when keys pay. Bookmarking this for the next design review."),
                comment("c-0024", "fc120202-9304-0500-9125-623325172311", "Sam",
                        "Thin controllers turned our integration tests from routing tests into behavior tests overnight."),
                comment("c-0025", "fc120202-9304-0500-9125-623325172311", "Mira",
                        "\"Versioning becomes a mapping concern instead of a rewrite\" — the v2 demo above proves it on the same use cases."),
                comment("c-0026", "eb010101-8203-9400-8014-514224061312", "Devon",
                        "The journey diagram is still the fastest onboarding artifact we have. Every new endpoint is a variation, exactly as promised."),
                comment("c-0027", "eb010101-8203-9400-8014-514224061312", "Priya",
                        "Teaching the pipeline once beats explaining routing five times a month. Meta but true."));
    }

    private static Comment comment(String id, String postId, String author, String body) {
        return new Comment(id, postId, author, body);
    }
}

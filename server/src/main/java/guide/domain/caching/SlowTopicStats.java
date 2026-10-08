package guide.domain.caching;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.posts.ListPosts;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostSort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The expensive read itself: page through EVERY post carrying a tag and
 * reduce it to word-count analytics. It is not algorithmically slow — it is
 * DELIBERATELY slow: one {@link Delayer#pause()} per compute stands in for
 * the gnarly aggregate / fan-out / slow neighbor a real cache fronts, so the
 * demo can show a cache saving work you can feel, and tests can inject
 * {@link Delayer#none()} and still prove everything with counts.
 *
 * computeCount() is incremented ONCE PER COMPUTE, before the paging starts —
 * the number the caching wrappers diff to classify HIT vs MISS.
 */
public final class SlowTopicStats implements TopicStats {

    /** One page per listing call; the seed has 12 posts, a real corpus would page on. */
    static final int PAGE_SIZE = 50;

    private final ListPosts listPosts;
    private final Delayer delayer;
    private final AtomicLong computes = new AtomicLong();

    public SlowTopicStats(ListPosts listPosts, Delayer delayer) {
        this.listPosts = Objects.requireNonNull(listPosts, "listPosts");
        this.delayer = Objects.requireNonNull(delayer, "delayer");
    }

    @Override
    public TopicStatsReport compute(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            throw new IllegalArgumentException("topicId must be a non-blank string");
        }
        delayer.pause();
        computes.incrementAndGet();

        List<Post> posts = fetchAllTagged(topicId.strip());
        if (posts.isEmpty()) {
            return TopicStatsReport.empty(topicId);
        }
        long totalWords = 0;
        int minWords = Integer.MAX_VALUE;
        int maxWords = 0;
        String longestPostTitle = "";
        for (Post post : posts) {
            int words = words(post.body());
            totalWords += words;
            minWords = Math.min(minWords, words);
            if (words > maxWords) {
                maxWords = words;
                longestPostTitle = post.title();
            }
        }
        double avg = Math.round(totalWords * 10.0 / posts.size()) / 10.0;
        return new TopicStatsReport(topicId, posts.size(), totalWords,
                minWords, maxWords, avg, longestPostTitle);
    }

    @Override
    public long computeCount() {
        return computes.get();
    }

    /** Pages the listing until exhausted — the N queries a cache collapses into zero. */
    private List<Post> fetchAllTagged(String topicId) {
        List<Post> posts = new ArrayList<>();
        int page = 0;
        while (true) {
            Result<PagedPosts> outcome = listPosts.execute(page, PAGE_SIZE,
                    PostSort.DEFAULT, new PostFilter(null, topicId));
            // Defensive: page ≥ 0 and size ≤ MAX_PAGE_SIZE cannot fail validation —
            // a Failed here means the repository itself is down, which is the
            // typed-unavailable story, surfaced as the taxonomy's envelope.
            if (outcome instanceof Failed<PagedPosts> failed) {
                throw AppException.of(failed.failure());
            }
            List<Post> items = ((Success<PagedPosts>) outcome).value().items();
            if (items.isEmpty()) {
                return posts;
            }
            posts.addAll(items);
            page += 1;
        }
    }

    /** Whitespace-separated tokens — the honest cheap definition of "a word". */
    static int words(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        return body.strip().split("\\s+").length;
    }
}

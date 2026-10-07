package guide.data.comments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.comments.Comment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The comments adapter's own contract: the deterministic seed (every seed
 * post has comments, three posts have three), the batch shape's promise
 * (every requested id is present in the answer, duplicates collapse), and
 * the call counters the N+1 proof relies on.
 */
class InMemoryCommentRepositoryTest {

    private final InMemoryCommentRepository repository = new InMemoryCommentRepository();

    private static final String SEED_POST = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    @Test
    void theSeedIsDeterministicAndCoversEveryPost() {
        InMemoryCommentRepository fresh = new InMemoryCommentRepository();
        List<String> seedPostIds = List.of(
                "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01",
                "8e0b4941-2c6d-4e79-8a10-5b3c7d1b6e02",
                "7d2a3830-1b5c-4d68-790f-4a2b6c0a5d03",
                "6c392729-0a4b-4c57-681e-391a5b094c04",
                "5b481618-f93a-4b46-572d-280949083b05",
                "4a570507-e829-4a35-463c-17283a7272a0",
                "39660406-d718-4924-354b-063729616107",
                "28753305-c607-3813-2459-950628505008",
                "17842204-b506-2702-1347-841527394109",
                "06931103-a405-1601-0236-732426283210",
                "fc120202-9304-0500-9125-623325172311",
                "eb010101-8203-9400-8014-514224061312");
        for (String postId : seedPostIds) {
            int size = fresh.listForPost(postId).size();
            assertTrue(size >= 2 && size <= 3, "post " + postId + " carries 2-3 comments");
        }
        assertEquals(27, seedPostIds.stream()
                .mapToInt(id -> fresh.listForPost(id).size()).sum());
        assertEquals(fresh.listForPost(SEED_POST), repository.listForPost(SEED_POST));
    }

    @Test
    void theBatchShapeAnswersEveryKeyIncludingUnknowns() {
        Map<String, List<Comment>> byPost = repository.listForPosts(
                List.of(SEED_POST, "never-heard-of-it", SEED_POST));
        assertEquals(2, byPost.size(), "duplicate ids collapse to one entry");
        assertEquals(3, byPost.get(SEED_POST).size());
        assertEquals(List.of(), byPost.get("never-heard-of-it"),
                "an unknown post answers an empty list, not a missing key");
        assertEquals(1, repository.batchCallCount());
    }

    @Test
    void countersCountByShapeAndReset() {
        repository.resetCallCounts();
        repository.listForPost(SEED_POST);
        repository.listForPost(SEED_POST);
        repository.listForPosts(List.of(SEED_POST));
        assertEquals(2, repository.singleLookupCount());
        assertEquals(1, repository.batchCallCount());
        repository.resetCallCounts();
        assertEquals(0, repository.singleLookupCount());
        assertEquals(0, repository.batchCallCount());
    }
}

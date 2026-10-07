package guide.domain.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.content.ContentRegistry;
import guide.content.ContentSection;
import guide.content.TextBlock;
import guide.content.Topic;
import guide.content.TopicGroupId;
import guide.content.TopicSummary;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.data.content.ContentRepositoryAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentUseCasesTest {

    private static final Topic TOPIC = new Topic("spring-core", "Spring Core", "Why IoC",
            TopicGroupId.FUNDAMENTALS, 5, List.of(
                    new ContentSection("s1", "Container", "lead", List.of(new TextBlock("b1", "body"))),
                    new ContentSection("s2", "Terminal companion", null, List.of())));

    private final ContentRepository repository =
            new ContentRepositoryAdapter(new ContentRegistry(List.of(TOPIC)));
    private final ListTopicSummaries list = new ListTopicSummaries(repository);
    private final GetTopicById get = new GetTopicById(repository);

    @Test
    void listServesVersionGroupsAndSummaries() {
        Result<TopicList> result = list.execute();
        assertTrue(result.isSuccess());
        TopicList payload = ((guide.core.result.Success<TopicList>) result).value();
        assertEquals(1, payload.version());
        assertEquals(10, payload.groups().size());
        assertEquals("fundamentals", payload.groups().getFirst().id());
        assertEquals(1, payload.groups().getFirst().order());
        assertEquals(List.of(TopicSummary.from(TOPIC)), payload.topics());
    }

    @Test
    void blankIdFailsValidation() {
        Result<TopicDetail> result = get.execute("  ");
        assertFalse(result.isSuccess());
        assertEquals(FailureKind.VALIDATION, ((guide.core.result.Failed<TopicDetail>) result).failure().kind());
    }

    @Test
    void unknownIdFailsNotFoundWithTheTopicIdDetail() {
        Result<TopicDetail> result = get.execute("nope");
        assertFalse(result.isSuccess());
        guide.core.errors.Failure failure =
                ((guide.core.result.Failed<TopicDetail>) result).failure();
        assertEquals(FailureKind.NOT_FOUND, failure.kind());
        assertEquals("nope", failure.details().get("topicId"));
    }

    @Test
    void foundTopicComesBackVersionedAndComplete() {
        Result<TopicDetail> result = get.execute("spring-core");
        assertTrue(result.isSuccess());
        TopicDetail detail = ((guide.core.result.Success<TopicDetail>) result).value();
        assertEquals(1, detail.version());
        assertEquals("spring-core", detail.id());
        assertEquals(TopicGroupId.FUNDAMENTALS, detail.group());
        assertEquals(2, detail.sections().size());
    }
}

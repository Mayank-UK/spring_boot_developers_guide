package guide.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.content.CodeBlock;
import guide.content.ContentSection;
import guide.content.DemoBlock;
import guide.content.TextBlock;
import guide.content.Topic;
import guide.content.TopicGroupId;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.domain.content.GetTopicById;
import guide.domain.content.ListTopicSummaries;
import guide.domain.content.TopicDetail;
import guide.domain.content.TopicList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ContentController.class)
class ContentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListTopicSummaries listTopicSummaries;

    @MockitoBean
    private GetTopicById getTopicById;

    private static Topic topic() {
        return new Topic("spring-core", "Spring Core", "Why IoC", TopicGroupId.FUNDAMENTALS, 5,
                List.of(new ContentSection("s1", "Container", "The ApplicationContext", List.of(
                        new TextBlock("b-text", "Spring is containers all the way down."),
                        new CodeBlock("b-code", "java", "The registry validates itself",
                                "public class Registry {}", "server/src/main/java/guide/GuideApplication.java"),
                        new DemoBlock("b-demo", "core:container", "Container demo", null)))));
    }

    @Test
    void listServesVersionGroupsAndSummaries() throws Exception {
        TopicList payload = TopicList.of(
                List.of(guide.content.TopicSummary.from(topic())));
        when(listTopicSummaries.execute()).thenReturn(Result.success(payload));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.groups.length()").value(10))
                .andExpect(jsonPath("$.groups[0].id").value("fundamentals"))
                .andExpect(jsonPath("$.groups[0].title").value("Fundamentals"))
                .andExpect(jsonPath("$.groups[0].order").value(1))
                .andExpect(jsonPath("$.topics.length()").value(1))
                .andExpect(jsonPath("$.topics[0].id").value("spring-core"))
                .andExpect(jsonPath("$.topics[0].title").value("Spring Core"))
                .andExpect(jsonPath("$.topics[0].subtitle").value("Why IoC"))
                .andExpect(jsonPath("$.topics[0].group").value("fundamentals"))
                .andExpect(jsonPath("$.topics[0].order").value(5))
                .andExpect(jsonPath("$.topics[0].sectionCount").value(1))
                .andExpect(jsonPath("$.topics[0].sections").doesNotExist());
    }

    @Test
    void detailServesTheFullTopicWithTypedBlocks() throws Exception {
        TopicDetail payload = TopicDetail.of(topic());
        when(getTopicById.execute("spring-core")).thenReturn(Result.success(payload));

        mockMvc.perform(get("/api/content/spring-core"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.id").value("spring-core"))
                .andExpect(jsonPath("$.group").value("fundamentals"))
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].id").value("s1"))
                .andExpect(jsonPath("$.sections[0].lead").value("The ApplicationContext"))
                .andExpect(jsonPath("$.sections[0].blocks.length()").value(3))
                .andExpect(jsonPath("$.sections[0].blocks[0].kind").value("text"))
                .andExpect(jsonPath("$.sections[0].blocks[0].body").exists())
                .andExpect(jsonPath("$.sections[0].blocks[1].kind").value("code"))
                .andExpect(jsonPath("$.sections[0].blocks[1].language").value("java"))
                .andExpect(jsonPath("$.sections[0].blocks[1].source").exists())
                .andExpect(jsonPath("$.sections[0].blocks[2].kind").value("demo"))
                .andExpect(jsonPath("$.sections[0].blocks[2].demoId").value("core:container"))
                .andExpect(jsonPath("$.sections[0].blocks[2].description").doesNotExist());
    }

    @Test
    void unknownTopicIdYieldsThe404ErrorEnvelope() throws Exception {
        Result<TopicDetail> failed =
                new Failed<>(Failure.of(FailureKind.NOT_FOUND, "no topic with id \"nope\"", Map.of("topicId", "nope")));
        when(getTopicById.execute("nope")).thenReturn(failed);

        mockMvc.perform(get("/api/content/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.message").value("no topic with id \"nope\""))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.topicId").value("nope"));
    }
}

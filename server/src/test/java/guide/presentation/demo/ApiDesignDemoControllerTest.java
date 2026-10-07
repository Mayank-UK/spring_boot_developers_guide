package guide.presentation.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.PostsDemoConfig;
import guide.core.scenario.ScenarioPatch;
import guide.core.scenario.ScenarioStore;
import guide.data.posts.InMemoryPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc over the posts API with the REAL demo beans — the full HTTP matrix
 * the topic teaches, asserted endpoint by endpoint: listing defaults and
 * paging math, filtering and sorting (valid and hostile), the 201 + Location
 * + ETag creation contract, optimistic concurrency through the real ETag /
 * If-Match round trip (including the 409 that names the current version),
 * 204/404 deletion, the v2 mapping, and the ScenarioStore's 503 injection.
 * The repository is reset to its deterministic seed before every test, so
 * seed-addressing assertions never depend on test order.
 */
@WebMvcTest(ApiDesignDemoController.class)
@Import(PostsDemoConfig.class)
class ApiDesignDemoControllerTest {

    private static final String LIST_URL = "/api/demo/api/posts";
    private static final String NEWEST_SEED = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    @Autowired
    private MockMvc mockMvc;

    /** Phase 6: the exposed PostRepository bean is the router; the FAKE is its own bean, reset through its concrete type. */
    @Autowired
    private InMemoryPostRepository postRepository;

    @Autowired
    private ScenarioStore scenarioStore;

    /** The slice has no GuideConfig — the store PostsDemoConfig needs comes from here. */
    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @BeforeEach
    void resetDeterministicSeed() {
        postRepository.reset();
        scenarioStore.reset();
    }

    // ---- GET the collection: defaults, paging, sorting, filtering ----

    @Test
    void defaultListingServesTheSeedNewestFirstWithHonestTotals() throws Exception {
        mockMvc.perform(get(LIST_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].id").value(NEWEST_SEED))
                .andExpect(jsonPath("$.items[0].createdAt").exists());
    }

    @Test
    void pagingSortingAndFilteringComposeOnOneUrl() throws Exception {
        mockMvc.perform(get(LIST_URL).param("page", "1").param("size", "5")
                        .param("sort", "title,asc").param("status", "published").param("tag", "http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                // page 1 of a 3-element result at size 5 is beyond range: empty, honest totals
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void hostileQueryValuesAnswer422WithPerFieldReasons() throws Exception {
        mockMvc.perform(get(LIST_URL).param("sort", "author,asc"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.fields.sort").exists())
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());

        mockMvc.perform(get(LIST_URL).param("status", "archived"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.fields.status").exists());

        mockMvc.perform(get(LIST_URL).param("size", "101"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.fields.size").exists());
    }

    @Test
    void beyondRangePageIsAnEmptyPageNotAnError() throws Exception {
        mockMvc.perform(get(LIST_URL).param("page", "99").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    // ---- GET one: 200 + ETag, or the 404 envelope ----

    @Test
    void getOneAnswersTheRepresentationAndItsVersionAsETag() throws Exception {
        mockMvc.perform(get(LIST_URL + "/" + NEWEST_SEED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NEWEST_SEED))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(header().string("ETag", "W/\"1\""));
    }

    @Test
    void unknownIdAnswersThe404Envelope() throws Exception {
        mockMvc.perform(get(LIST_URL + "/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.postId").exists())
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    // ---- POST: 201 + Location + ETag, or 422 semantic validation ----

    @Test
    void createAnswers201WithLocationAndTheETagOfVersionOne() throws Exception {
        MvcResult result = mockMvc.perform(post(LIST_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Fielding was right about one thing",
                                 "body":"The uniform interface pays rent every single day.",
                                 "tags":[" api-design ","api-design"]}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "W/\"1\""))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("api-design"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertTrue(location != null && location.startsWith(LIST_URL + "/"),
                "Location names the created resource: " + location);
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Fielding was right about one thing"));
    }

    @Test
    void createJudgesSemanticsWith422AndTheStandardEnvelope() throws Exception {
        mockMvc.perform(post(LIST_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\",\"body\":\"b\",\"status\":\"archived\"}"
                                .formatted("x".repeat(121))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.fields.title").exists())
                .andExpect(jsonPath("$.error.fields.status").exists())
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    @Test
    void unreadableBodiesStay400ViaTheGlobalHandler() throws Exception {
        mockMvc.perform(post(LIST_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("request body is not readable JSON"));
    }

    // ---- PUT: optimistic concurrency end to end ----

    @Test
    void updateRoundTripSucceedsThenConflictsOnTheStaleVersion() throws Exception {
        String id = createPost("The contested post");

        mockMvc.perform(put(LIST_URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"The contested post, revised","body":"new body",
                                 "status":"published","version":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(header().string("ETag", "W/\"2\""));

        // The stale replay: same version 1 against the now-current version 2.
        mockMvc.perform(put(LIST_URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"The contested post, revised again","body":"newer body",
                                 "status":"published","version":1}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"))
                .andExpect(jsonPath("$.error.expectedVersion").value(1))
                .andExpect(jsonPath("$.error.currentVersion").value(2))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());

        // The stored content is the winner's, not the loser's.
        mockMvc.perform(get(LIST_URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("The contested post, revised"));
    }

    @Test
    void ifMatchHeaderIsTheETagSpellingOfTheSameContract() throws Exception {
        String id = createPost("If-Match post");

        mockMvc.perform(put(LIST_URL + "/" + id)
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Replaced via If-Match\",\"body\":\"b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(put(LIST_URL + "/" + id)
                        .header("If-Match", "not-a-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nope\",\"body\":\"b\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.fields.ifMatch").exists());
    }

    @Test
    void updateWithoutAnyVersionStatementIsRejectedAndUnknownIdsAre404() throws Exception {
        mockMvc.perform(put(LIST_URL + "/" + NEWEST_SEED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Versionless\",\"body\":\"b\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.fields.version").exists());

        mockMvc.perform(put(LIST_URL + "/ffffffff-ffff-ffff-ffff-ffffffffffff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ghost\",\"body\":\"b\",\"version\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    // ---- DELETE: 204, then an honest 404 ----

    @Test
    void deleteAnswers204Then404ForTheSameId() throws Exception {
        String id = createPost("Doomed post");

        mockMvc.perform(delete(LIST_URL + "/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(LIST_URL + "/" + id))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(LIST_URL + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    // ---- v2: same use cases, one added field ----

    @Test
    void v2ServesTheSameResourcesWithTheAddedSummaryField() throws Exception {
        mockMvc.perform(get("/api/demo/api/v2/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.items[0].id").value(NEWEST_SEED))
                .andExpect(jsonPath("$.items[0].summary").isNotEmpty())
                .andExpect(jsonPath("$.items[0].title").exists());

        mockMvc.perform(get("/api/demo/api/v2/posts/" + NEWEST_SEED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/demo/api/v2/posts/ffffffff-ffff-ffff-ffff-ffffffffffff"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    // ---- the ScenarioStore exhibit: injected dependency failure ----

    @Test
    void failureInjectionAnswersThe503EnvelopeOnEveryVerb() throws Exception {
        scenarioStore.apply(new ScenarioPatch(null, true));

        mockMvc.perform(get(LIST_URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.area").value("api"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());

        String id = NEWEST_SEED;
        mockMvc.perform(delete(LIST_URL + "/" + id))
                .andExpect(status().isServiceUnavailable());
        assertEquals(12, postRepository
                        .list(0, 100, guide.domain.posts.PostSort.DEFAULT,
                                guide.domain.posts.PostFilter.NONE).totalElements(),
                "an injected 503 must not have mutated anything");
    }

    // ---- helper ----

    /** Creates one post through the real endpoint and returns its id. */
    private String createPost(String title) throws Exception {
        MvcResult result = mockMvc.perform(post(LIST_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\",\"body\":\"body\"}".formatted(title)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"")
                .matcher(body).results().findFirst().orElseThrow().group(1);
    }
}

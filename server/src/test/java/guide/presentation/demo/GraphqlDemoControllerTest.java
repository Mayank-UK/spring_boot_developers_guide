package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Topic 13's playground endpoints against the REAL application context:
 * the catalog serves every preset (each with teaching commentary — the N+1
 * probe, the aliases/fragments specimen, the mutation, the error demo), and
 * the run endpoint executes documents through the same
 * ExecutionGraphQlService that serves /graphql — the alias/fragment preset
 * proves BOTH GraphQL client features in one response (drafts: and
 * published: keys shaped by one fragment), and the error preset proves the
 * taxonomy reaches the browser as data (extensions.code + requestId).
 *
 * The posts repository is reset before every test: the context (and its
 * singletons) is cached across test classes, so counts assert exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GraphqlDemoControllerTest {

    private static final String QUERIES = "/api/demo/graphql/queries";
    private static final String RUN = "/api/demo/graphql/run";

    @Autowired
    private MockMvc mockMvc;

    /** Phase 6: the exposed PostRepository bean is the router; the fake is reset through its own bean. */
    @Autowired
    private guide.data.posts.InMemoryPostRepository postRepository;

    @Autowired
    private guide.core.scenario.ScenarioStore scenarioStore;

    @BeforeEach
    void resetDeterministicSeed() {
        postRepository.reset();
        scenarioStore.reset();
    }

    @Test
    void theCatalogServesEveryTeachingPreset() throws Exception {
        mockMvc.perform(get(QUERIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.id == 'n-plus-one-probe')].description")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("ONE"))))
                .andExpect(jsonPath("$[?(@.id == 'aliases-and-fragments')].query")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("fragment pageShape"))))
                .andExpect(jsonPath("$[?(@.id == 'mutation-create')].query")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("createPost"))))
                .andExpect(jsonPath("$[?(@.id == 'error-envelope')].query")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("does-not-exist"))));
    }

    @Test
    void runExecutesTheAliasesAndFragmentsPresetForReal() throws Exception {
        String query = """
                query CompareStatuses {
                  drafts: posts(page: 0, size: 2, status: DRAFT) { ...pageShape }
                  published: posts(page: 0, size: 2, status: PUBLISHED) { ...pageShape }
                }

                fragment pageShape on PostPage {
                  totalElements
                  items { title status }
                }""";
        mockMvc.perform(post(RUN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":" + quote(query) + ", \"variables\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drafts.totalElements").value(2))
                .andExpect(jsonPath("$.data.published.totalElements").value(10))
                .andExpect(jsonPath("$.data.drafts.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.published.items[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void runCarriesTheErrorTaxonomyIntoTheOutcome() throws Exception {
        mockMvc.perform(post(RUN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":" + quote(
                                "query { post(id: \"nope\") { id } }") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post").doesNotExist())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("not_found"))
                .andExpect(jsonPath("$.errors[0].extensions.requestId").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].path[0]").value("post"));
    }

    @Test
    void runWithoutAQueryIsTheStandardValidationEnvelope() throws Exception {
        mockMvc.perform(post(RUN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    /** Minimal JSON string quoting for embedding a GraphQL document in a body. */
    private static String quote(String document) {
        String escaped = document.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }
}

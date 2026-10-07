package guide.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * THE DRIFT TEST (topic 12, api-documentation) — the keeping-docs-honest
 * contract, executable. It GETs the live /v3/api-docs document this build
 * actually serves and pins the core of the documented surface:
 *
 * - the document is OpenAPI 3.1 and carries the guide's title;
 * - the core paths are present (content, health, the posts CRUD) — rename a
 *   route and this goes red BEFORE any client does;
 * - the posts operations keep their stable operationIds (client generators
 *   key on them) and the Post schema carries the resource's real fields;
 * - Swagger UI mounts at /docs, the guide-family convention.
 *
 * Deliberate scope: it pins the CURATED surface (what the topic documents),
 * not every demo endpoint — siblings keep adding those, and presence here is
 * the contract that matters. Rename or delete anything below without
 * updating this test and the suite fails: that is the entire point.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecDriftTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theDocumentIsOpenApi31AndNamesTheApi() throws Exception {
        JsonNode spec = fetchSpec();
        assertEquals("3.1.0", spec.path("openapi").asString(),
                "springdoc 3.x must emit an OpenAPI 3.1 document");
        assertEquals("Spring Boot Developer's Guide API", spec.path("info").path("title").asString(),
                "the ApiDocsDemoConfig info bean names the API");
    }

    @Test
    void theCorePathsArePresent() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/health']").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/content']").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/content/{topicId}']").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/demo/api/posts']").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/demo/api/posts/{id}']").isNotEmpty());
    }

    @Test
    void postsOperationsKeepStableIdsAndTheRealSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/demo/api/posts'].get.operationId").value("listPosts"))
                .andExpect(jsonPath("$.paths['/api/demo/api/posts'].post.operationId").value("createPost"))
                .andExpect(jsonPath("$.paths['/api/demo/api/posts/{id}'].get.operationId").value("getPost"))
                .andExpect(jsonPath("$.paths['/api/demo/api/posts/{id}'].put.operationId").value("updatePost"))
                .andExpect(jsonPath("$.paths['/api/demo/api/posts/{id}'].delete.operationId").value("deletePost"))
                .andExpect(jsonPath("$.components.schemas.Post.properties.id").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.Post.properties.title").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.Post.properties.body").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.Post.properties.tags").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.Post.properties.status").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.Post.properties.version").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.PagedPosts").isNotEmpty())
                .andExpect(jsonPath("$.tags[?(@.name == 'Posts')]").isNotEmpty());
    }

    @Test
    void swaggerUiMountsAtTheFamilyConventionPath() throws Exception {
        // /docs is springdoc's swagger-ui.path redirect to the UI page — the
        // README-promised mount, one property in application.yml.
        mockMvc.perform(get("/docs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }

    private JsonNode fetchSpec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = JSON.readTree(body);
        assertTrue(spec.has("paths"), "the document must carry a paths object");
        return spec;
    }
}

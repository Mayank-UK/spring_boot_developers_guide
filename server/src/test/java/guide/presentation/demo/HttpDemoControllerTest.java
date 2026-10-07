package guide.presentation.demo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.HttpDemoConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the http-web inspector with the REAL demo beans imported —
 * the full round trip: servlet request → controller extraction → use case →
 * exhibit → JSON. Custom headers come back classified, Authorization is
 * flagged sensitive, the POST variant reports its parsed Content-Type, and
 * unknown query parameters are tolerated, not rejected.
 */
@WebMvcTest(HttpDemoController.class)
@Import(HttpDemoConfig.class)
class HttpDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void customHeadersRoundTripSortedAndClassified() throws Exception {
        mockMvc.perform(get("/api/demo/http/inspect")
                        .queryParam("channel", "terminal")
                        .header("x-teaching-flag", "demo-123")
                        .header("If-None-Match", "\"etag-v1\"")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.path").value("/api/demo/http/inspect"))
                .andExpect(jsonPath("$.query").value("channel=terminal"))
                .andExpect(jsonPath("$.headers[?(@.name == 'x-teaching-flag')].value")
                        .value(hasItem("demo-123")))
                .andExpect(jsonPath("$.headers[?(@.name == 'x-teaching-flag')].category")
                        .value(hasItem("other")))
                .andExpect(jsonPath("$.headers[?(@.name == 'If-None-Match')].category")
                        .value(hasItem("caching")))
                .andExpect(jsonPath("$.headers[?(@.name == 'Accept')].category")
                        .value(hasItem("negotiation")))
                .andExpect(jsonPath("$.headers[?(@.name == 'x-teaching-flag')].known")
                        .value(hasItem(false)))
                .andExpect(jsonPath("$.body.present").value(false))
                .andExpect(jsonPath("$.body.parsedAs").value("absent"));
    }

    @Test
    void secretBearingHeadersEchoWithTheSensitiveFlag() throws Exception {
        mockMvc.perform(get("/api/demo/http/inspect")
                        .header("Authorization", "Bearer demo-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers[?(@.name == 'Authorization')].category")
                        .value(hasItem("auth")))
                .andExpect(jsonPath("$.headers[?(@.name == 'Authorization')].sensitive")
                        .value(hasItem(true)))
                .andExpect(jsonPath("$.headers[?(@.name == 'Authorization')].value")
                        .value(hasItem("Bearer demo-token")));
    }

    @Test
    void postVariantReportsTheParsedContentType() throws Exception {
        mockMvc.perform(post("/api/demo/http/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\":\"wire\",\"count\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.body.present").value(true))
                .andExpect(jsonPath("$.body.mediaType").value("application/json"))
                .andExpect(jsonPath("$.body.contentType", containsString("application/json")))
                .andExpect(jsonPath("$.body.parsedAs").value("json object"))
                .andExpect(jsonPath("$.body.topLevelEntries").value(2))
                .andExpect(jsonPath("$.headers[?(@.name == 'Content-Type')].category")
                        .value(hasItem("content")));
    }

    @Test
    void unknownQueryParametersAreTolerated() throws Exception {
        mockMvc.perform(get("/api/demo/http/inspect")
                        .queryParam("whatever", "1")
                        .queryParam("noise", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("whatever=1&noise=x"));
    }
}

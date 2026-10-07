package guide.presentation.demo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ReqResDemoConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc over the reqres demos with the REAL demo beans imported. The
 * inspector proves the two surfaces: sugars (@RequestParam/@RequestHeader/
 * @CookieValue/@RequestBody) come back stamped with the annotation that
 * carried them, while the raw half reports URI/query/content type read by
 * hand. The methods matrix asserts all five verbs land their exact status
 * and signature headers — the decision made in the domain, mapped at the
 * edge. The stream endpoint runs through Spring's real async dispatch.
 */
@WebMvcTest(ReqResDemoController.class)
@Import(ReqResDemoConfig.class)
class ReqResDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- the inspector: binding sugars beside the raw surface ----

    @Test
    void boundParamsRoundTripStampedWithTheirSugar() throws Exception {
        mockMvc.perform(get("/api/demo/reqres/inspect")
                        .queryParam("channel", "terminal")
                        .queryParam("verbose", "yes")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("x-teaching-flag", "from-curl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.bound.params[?(@.name == 'channel')].value")
                        .value(org.hamcrest.Matchers.hasItem("terminal")))
                .andExpect(jsonPath("$.bound.params[?(@.name == 'channel')].via")
                        .value(org.hamcrest.Matchers.hasItem("@RequestParam")))
                .andExpect(jsonPath("$.bound.params[?(@.name == 'verbose')].via")
                        .value(org.hamcrest.Matchers.hasItem("@RequestParam")))
                .andExpect(jsonPath("$.bound.headers[?(@.name == 'Accept')].via")
                        .value(org.hamcrest.Matchers.hasItem("@RequestHeader")))
                .andExpect(jsonPath("$.bound.headers[?(@.name == 'x-teaching-flag')].value")
                        .value(org.hamcrest.Matchers.hasItem("from-curl")));
    }

    @Test
    void rawSurfaceReportsUriQueryStringAndContentTypeAccurately() throws Exception {
        mockMvc.perform(get("/api/demo/reqres/inspect")
                        .queryParam("channel", "terminal")
                        .header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.raw.requestURI").value("/api/demo/reqres/inspect"))
                .andExpect(jsonPath("$.raw.queryString").value("channel=terminal"))
                .andExpect(jsonPath("$.raw.authType").doesNotExist())
                .andExpect(jsonPath("$.attributes").isArray())
                .andExpect(jsonPath("$.summary.notes[0]").value(
                        "the query string was never parsed by hand — @RequestParam had already bound it"));
    }

    @Test
    void cookieBindsViaItsSugar() throws Exception {
        mockMvc.perform(get("/api/demo/reqres/inspect")
                        .cookie(new Cookie("reqres-visit", "first-visit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bound.cookies[?(@.name == 'reqres-visit')].value")
                        .value(org.hamcrest.Matchers.hasItem("first-visit")))
                .andExpect(jsonPath("$.bound.cookies[?(@.name == 'reqres-visit')].via")
                        .value(org.hamcrest.Matchers.hasItem("@CookieValue")));
    }

    @Test
    void postVariantBindsTheWholeBodyAfterConverterNegotiation() throws Exception {
        mockMvc.perform(post("/api/demo/reqres/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\":\"wire\",\"count\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.bound.body.present").value(true))
                .andExpect(jsonPath("$.bound.body.parsedAs").value("object"))
                .andExpect(jsonPath("$.bound.body.topLevelEntries").value(2))
                .andExpect(jsonPath("$.raw.contentType", containsString("application/json")));
    }

    // ---- the method matrix: one path, five verbs, exact statuses ----

    @Test
    void getAnswers200WithRepresentation() throws Exception {
        mockMvc.perform(get("/api/demo/reqres/methods").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("x-teaching-note",
                        "200 OK — success with a representation"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.semantics").value("safe · idempotent"));
    }

    @Test
    void postAnswers201WithLocation() throws Exception {
        mockMvc.perform(post("/api/demo/reqres/methods"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        matchesPattern("/api/demo/reqres/methods/resource-\\d+")))
                .andExpect(header().string("x-teaching-note",
                        "201 Created — Location names the new resource"))
                .andExpect(jsonPath("$.facts.created").value(matchesPattern("resource-\\d+")));
    }

    @Test
    void putAnswers200WithAnEtagHeader() throws Exception {
        mockMvc.perform(put("/api/demo/reqres/methods"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", matchesPattern("^\"v\\d+\"$")))
                .andExpect(jsonPath("$.facts.etag").value(matchesPattern("^\"v\\d+\"$")));
    }

    @Test
    void patchAnswers204WithNoBody() throws Exception {
        mockMvc.perform(patch("/api/demo/reqres/methods"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("x-teaching-note",
                        "204 No Content — applied; deliberately nothing to say"));
    }

    @Test
    void deleteAnswers200WithConfirmationBody() throws Exception {
        mockMvc.perform(delete("/api/demo/reqres/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("DELETE"))
                .andExpect(jsonPath("$.facts.deleted").value("true"));
    }

    // ---- streaming: real async dispatch, chunks flushed as computed ----

    @Test
    void streamEndpointStreamsChunksThroughAsyncDispatch() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/demo/reqres/stream").queryParam("lines", "3"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(); // let the StreamingResponseBody task finish writing

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("chunk 1 of 3")))
                .andExpect(content().string(containsString("chunk 3 of 3")));
    }

    @Test
    void streamRejectsOutOfRangeLineCounts() throws Exception {
        mockMvc.perform(get("/api/demo/reqres/stream").queryParam("lines", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- cookies + redirects ----

    @Test
    void cookieTourSetsAttributesAndRedirects() throws Exception {
        mockMvc.perform(get("/api/demo/reqres/cookie"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/api/demo/reqres/inspect?channel=cookie-tour"))
                .andExpect(header().string("Set-Cookie", containsString("reqres-visit=set-by-tour")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/demo/reqres")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=3600")));
    }
}

package guide.presentation.demo;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ErrorsDemoConfig;
import guide.core.errors.FailureKind;
import guide.domain.errors.InjectFailure;
import guide.domain.errors.UncaughtFailureLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the failure explorer with the REAL use cases (they are pure
 * and deterministic — nothing to fake) and the REAL advice: every assertion
 * here observes what the global handler actually renders, because /inject
 * and /problem-detail genuinely throw and let the advice answer. This is
 * also where the topic's parked fix is pinned end-to-end INSIDE a slice:
 * an unrouted path renders the typed 404 envelope and a wrong method the
 * typed 405 — plus the process net's registration side effect.
 */
@WebMvcTest(ErrorsDemoController.class)
@Import(ErrorsDemoConfig.class)
class ErrorsDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InjectFailure injectFailure;

    @Test
    void catalogListsTheEightKindsPlusTheFrameworkFamily() throws Exception {
        mockMvc.perform(get("/api/demo/errors/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].kind").value("VALIDATION"))
                .andExpect(jsonPath("$[0].raisedBy").value("ValidationException"))
                .andExpect(jsonPath("$[0].httpStatus").value(400))
                .andExpect(jsonPath("$[0].envelopeCode").value("validation_failed"))
                .andExpect(jsonPath("$[0].injectable").value(true))
                .andExpect(jsonPath("$[7].kind").value("INTERNAL"))
                .andExpect(jsonPath("$[8].kind").value("NoResourceFoundException"))
                .andExpect(jsonPath("$[8].httpStatus").value(404))
                .andExpect(jsonPath("$[8].envelopeCode").value("not_found"))
                .andExpect(jsonPath("$[8].injectable").value(false))
                .andExpect(jsonPath("$[8].reproduce").isNotEmpty())
                .andExpect(jsonPath("$[9].kind").value("HttpRequestMethodNotSupportedException"))
                .andExpect(jsonPath("$[9].httpStatus").value(405))
                .andExpect(jsonPath("$[9].envelopeCode").value("method_not_allowed"));
    }

    @Test
    void catalogPromisesExactlyWhatInjectRenders() throws Exception {
        // single-source check: the example message column IS the descriptor's message
        String promised = injectFailure.failureOf(FailureKind.CONFLICT, true).message();
        mockMvc.perform(get("/api/demo/errors/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[4].exampleMessage").value(promised));
    }

    @Test
    void everyKindRendersItsOwnStatusAndEnvelope() throws Exception {
        for (FailureKind kind : FailureKind.values()) {
            mockMvc.perform(post("/api/demo/errors/inject").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"" + kind.name() + "\"}"))
                    .andExpect(status().is(kind.httpStatus()))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.code").value(kind.errorCode()))
                    .andExpect(jsonPath("$.error.message")
                            .value(injectFailure.failureOf(kind, false).message()))
                    .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        }
    }

    @Test
    void includeDetailsMergesTheCanonicalDetailsOrLeavesThemOut() throws Exception {
        mockMvc.perform(post("/api/demo/errors/inject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NOT_FOUND\",\"includeDetails\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.postId").value("no-such-post"));

        mockMvc.perform(post("/api/demo/errors/inject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NOT_FOUND\",\"includeDetails\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.postId").doesNotExist());
    }

    @Test
    void internalNeverLeaksDetailsEvenWhenAsked() throws Exception {
        mockMvc.perform(post("/api/demo/errors/inject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"INTERNAL\",\"includeDetails\":true}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                // leak discipline: the 500 envelope carries exactly the three canonical keys
                .andExpect(jsonPath("$.error.length()").value(3));
    }

    @Test
    void anUnknownKindIsItselfATypedValidationFailure() throws Exception {
        mockMvc.perform(post("/api/demo/errors/inject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.field").value("kind"))
                .andExpect(jsonPath("$.error.validKinds").isNotEmpty());
    }

    @Test
    void problemDetailRendersTheRfc9457ShapeForTheSameFailure() throws Exception {
        mockMvc.perform(get("/api/demo/errors/problem-detail")
                        .param("kind", "CONFLICT").param("includeDetails", "true"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value(injectFailure.failureOf(FailureKind.CONFLICT, false).message()))
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.slug").value("first-post"))
                .andExpect(jsonPath("$.type").isNotEmpty());
    }

    @Test
    void theTwoRenditionsCarryTheSameFailureInDifferentShapes() throws Exception {
        // envelope: {error:{code,message,requestId,...details}} — the app-wide contract
        mockMvc.perform(post("/api/demo/errors/inject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CONFLICT\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("conflict"))
                .andExpect(jsonPath("$.error.slug").value("first-post"));

        // problem+json: {type,title,status,detail,...properties} — the standard shape
        mockMvc.perform(get("/api/demo/errors/problem-detail")
                        .param("kind", "CONFLICT").param("includeDetails", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.slug").value("first-post"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void anUnroutedPathIsNowTheTyped404Envelope() throws Exception {
        // the parked fix, pinned: NoResourceFoundException keeps its own status
        mockMvc.perform(get("/api/definitely/not/routed"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.title").value("Not Found"))
                .andExpect(jsonPath("$.error.message").value("No static resource api/definitely/not/routed."));
    }

    @Test
    void aWrongMethodIsNowTheTyped405EnvelopeWithAllowSurviving() throws Exception {
        mockMvc.perform(post("/api/demo/errors/catalog"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("method_not_allowed"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.message").value("Method 'POST' is not supported."))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void theProcessNetIsInstalledWithTheDemoWiring() {
        assertInstanceOf(UncaughtFailureLogger.class, Thread.getDefaultUncaughtExceptionHandler(),
                "ErrorsDemoConfig must install the JVM-wide uncaught-exception handler");
    }
}

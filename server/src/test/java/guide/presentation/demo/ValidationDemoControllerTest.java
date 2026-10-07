package guide.presentation.demo;

import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ValidationDemoConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc over the validation arena with the REAL demo beans (and the REAL
 * auto-configured validator) — the full round trip: JSON → Jackson → record
 * → Bean Validation → verdict. The analyst endpoint answers 200 with a
 * per-field verdict for anything that parses; the strict endpoint exercises
 * Spring's @Valid path through the global handler's
 * MethodArgumentNotValidException branch; a body that never parses becomes
 * the 400 envelope. Parity between the arena's envelope preview and the
 * strict endpoint's real envelope is asserted on the same payload.
 */
@WebMvcTest(ValidationDemoController.class)
@Import(ValidationDemoConfig.class)
class ValidationDemoControllerTest {

    private static final String VALID_BODY = """
            {"name":"Ada Lovelace","email":"ada@example.com","score":87,
             "eventDate":"2020-01-15","password":"Sup3r$ecret!",
             "confirmStart":"2026-11-01","confirmEnd":"2026-11-08"}""";

    private static final String HOSTILE_BODY = """
            {"name":"","email":"not-an-email","score":101,
             "eventDate":"2030-01-01","password":"",
             "confirmStart":"2026-11-08","confirmEnd":"2026-11-01"}""";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validPayloadIsAcceptedWithEveryFieldClassifiedAccepted() throws Exception {
        mockMvc.perform(post("/api/demo/validation/arena")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.perField.length()").value(7))
                .andExpect(jsonPath("$.perField[?(@.field == 'eventDate')].valueClassification")
                        .value(org.hamcrest.Matchers.hasItem("accepted")))
                .andExpect(jsonPath("$.envelopeComparison").doesNotExist());
    }

    @Test
    void everyViolationClassFiresItsAnnotationInTheAnalystVerdict() throws Exception {
        mockMvc.perform(post("/api/demo/validation/arena")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HOSTILE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.perField[?(@.field == 'name')].violations[0].annotation")
                        .value(org.hamcrest.Matchers.hasItem("NotBlank")))
                .andExpect(jsonPath("$.perField[?(@.field == 'email')].violations[0].annotation")
                        .value(org.hamcrest.Matchers.hasItem("Email")))
                .andExpect(jsonPath("$.perField[?(@.field == 'score')].violations[0].annotation")
                        .value(org.hamcrest.Matchers.hasItem("Max")))
                .andExpect(jsonPath("$.perField[?(@.field == 'eventDate')].violations[0].annotation")
                        .value(org.hamcrest.Matchers.hasItem("Past")))
                .andExpect(jsonPath("$.perField[?(@.field == 'password')].violations[*].annotation")
                        .value(org.hamcrest.Matchers.hasItems("NotBlank", "StrongPassword")))
                .andExpect(jsonPath("$.envelopeComparison.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.envelopeComparison.error.fields.name").value("name is required"));
    }

    @Test
    void classLevelBreachIsItsOwnVerdictRow() throws Exception {
        mockMvc.perform(post("/api/demo/validation/arena")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("2026-11-08", "2026-10-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath(
                        "$.perField[?(@.field == 'confirm window (confirmStart, confirmEnd)')].violations[0].annotation")
                        .value(org.hamcrest.Matchers.hasItem("DateRange")))
                .andExpect(jsonPath(
                        "$.perField[?(@.field == 'confirm window (confirmStart, confirmEnd)')].valueClassification")
                        .value(org.hamcrest.Matchers.hasItem("cross-field: confirmStart ≤ confirmEnd")));
    }

    @Test
    void strictEndpointRejectsInvalidBodiesWithTheStandardEnvelope() throws Exception {
        mockMvc.perform(post("/api/demo/validation/arena/strict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HOSTILE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("request body failed validation"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.fields", hasEntry("name", "name is required")))
                .andExpect(jsonPath("$.error.fields", hasEntry("score", "score must be at most 100")))
                .andExpect(jsonPath("$.error.fields", hasKey("password")))
                .andExpect(jsonPath("$.error.fields", not(hasKey("confirmStart"))));
    }

    @Test
    void strictEndpointAcceptsValidBodies() throws Exception {
        mockMvc.perform(post("/api/demo/validation/arena/strict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void arenaEnvelopePreviewMatchesTheStrictEndpointRealEnvelope() throws Exception {
        MvcResult strict = mockMvc.perform(post("/api/demo/validation/arena/strict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HOSTILE_BODY))
                .andExpect(status().isBadRequest())
                .andReturn();
        String strictFields = strict.getResponse().getContentAsString();

        mockMvc.perform(post("/api/demo/validation/arena")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HOSTILE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envelopeComparison.error.fields.name")
                        .value("name is required"))
                .andExpect(jsonPath("$.envelopeComparison.error.fields.score")
                        .value("score must be at most 100"))
                .andExpect(mvcResult -> {
                    String preview = mvcResult.getResponse().getContentAsString();
                    for (String fragment : new String[] {
                            "\"name\":\"name is required\"",
                            "\"score\":\"score must be at most 100\""}) {
                        org.junit.jupiter.api.Assertions.assertTrue(strictFields.contains(fragment)
                                        && preview.contains(fragment),
                                "preview and strict envelope agree on: " + fragment);
                    }
                });
    }

    @Test
    void malformedJsonIsA400EnvelopeNotA500() throws Exception {
        mockMvc.perform(post("/api/demo/validation/arena")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("request body is not readable JSON"));
    }

    @Test
    void theSubmittedPasswordValueNeverRidesTheWire() throws Exception {
        String body = "{\"name\":\"Ada\",\"email\":\"a@b.co\",\"score\":5,"
                + "\"password\":\"top-secret-hunter2\",\"confirmStart\":null,\"confirmEnd\":null}";
        MvcResult result = mockMvc.perform(post("/api/demo/validation/arena")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(
                result.getResponse().getContentAsString().contains("top-secret-hunter2"),
                "the verdict classifies the password, never echoes it");
    }
}

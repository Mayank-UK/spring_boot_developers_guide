package guide.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.errors.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kind→status mapping table test through the real advice, driven by a
 * probe controller that throws exactly what the app throws. Standalone
 * MockMvc keeps this pure advice-testing: no Spring context, no filters —
 * so the requestId fallback ("n/a") is exercised too.
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {

        @GetMapping("/probe")
        String probe(@RequestParam FailureKind kind) {
            throw AppException.of(Failure.of(kind, "injected failure of kind \"" + kind + "\""));
        }

        @GetMapping("/probe-details")
        String probeDetails() {
            throw new ValidationException("payload is invalid", Map.of("parameter", "body"));
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void everyFailureKindMapsToItsHttpStatusAndMachineCode() throws Exception {
        for (FailureKind kind : FailureKind.values()) {
            mockMvc.perform(get("/probe").param("kind", kind.name()))
                    .andExpect(status().is(kind.httpStatus()))
                    .andExpect(jsonPath("$.error.code").value(kind.errorCode()))
                    .andExpect(jsonPath("$.error.message").value("injected failure of kind \"" + kind + "\""))
                    .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        }
    }

    @Test
    void failureDetailsMergeIntoTheEnvelopeUnderCanonicalKeys() throws Exception {
        mockMvc.perform(get("/probe-details"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.parameter").value("body"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    @Test
    void unknownExceptionBecomesThe500EnvelopeWithoutLeakingTheMessage() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }
}

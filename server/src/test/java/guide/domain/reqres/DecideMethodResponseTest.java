package guide.domain.reqres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.reqres.MethodsExhibit.Decision;
import org.junit.jupiter.api.Test;

/**
 * MethodsExhibit + DecideMethodResponse unit tests: every verb maps to its
 * canonical status/headers/body decision, the decision is a VALUE (PATCH
 * carries no body, POST/PUT consume the live counters so repeated calls
 * visibly progress), and unsupported verbs answer a typed validation
 * failure — the same contract as every other failure in the app.
 */
class DecideMethodResponseTest {

    private final MethodsExhibit exhibit = new MethodsExhibit();
    private final DecideMethodResponse useCase = new DecideMethodResponse(exhibit);

    @Test
    void getAnswersTwoHundredWithRepresentation() {
        Decision decision = exhibit.decide("GET", 0, 0);
        assertEquals(200, decision.status());
        assertEquals("200 OK — success with a representation",
                decision.headers().get(MethodsExhibit.TEACHING_NOTE));
        assertEquals("safe · idempotent", decision.body().semantics());
        assertEquals("methods-demo", decision.body().facts().get("resource"));
    }

    @Test
    void postAnswersCreatedWithLocationNamingTheResource() {
        Decision decision = exhibit.decide("POST", 4, 0);
        assertEquals(201, decision.status());
        assertEquals("/api/demo/reqres/methods/resource-4", decision.headers().get("Location"));
        assertEquals("resource-4", decision.body().facts().get("created"));
    }

    @Test
    void putAnswersOkWithAnEtagThatVersionsTheReplacement() {
        Decision decision = exhibit.decide("PUT", 0, 9);
        assertEquals(200, decision.status());
        assertEquals("\"v9\"", decision.headers().get("ETag"));
        assertEquals("not safe · idempotent", decision.body().semantics());
    }

    @Test
    void patchAnswersNoContentWithDeliberatelyNoBody() {
        Decision decision = exhibit.decide("PATCH", 0, 0);
        assertEquals(204, decision.status());
        assertNull(decision.body(), "a 204 must not carry a body");
        assertTrue(decision.headers().containsKey(MethodsExhibit.TEACHING_NOTE));
    }

    @Test
    void deleteAnswersOkWithAConfirmationBody() {
        Decision decision = exhibit.decide("DELETE", 0, 0);
        assertEquals(200, decision.status());
        assertEquals("true", decision.body().facts().get("deleted"));
    }

    @Test
    void useCaseCountersProgressAcrossCalls() {
        Decision first = bodyOf(useCase.execute("POST"));
        Decision second = bodyOf(useCase.execute("POST"));
        assertTrue(first.headers().get("Location").endsWith("resource-1"));
        assertTrue(second.headers().get("Location").endsWith("resource-2"));

        Decision etagOne = bodyOf(useCase.execute("PUT"));
        Decision etagTwo = bodyOf(useCase.execute("PUT"));
        assertEquals("\"v1\"", etagOne.headers().get("ETag"));
        assertEquals("\"v2\"", etagTwo.headers().get("ETag"));
    }

    @Test
    void unsupportedVerbsAnswerATypedValidationFailure() {
        Result<Decision> result = useCase.execute("HEAD");
        assertTrue(result instanceof Failed<Decision>);
        assertEquals(FailureKind.VALIDATION, ((Failed<Decision>) result).failure().kind());
        assertThrows(IllegalArgumentException.class, () -> exhibit.decide("HEAD", 0, 0));
    }

    @Test
    void supportsIsCaseInsensitiveAndRefusesOtherVerbs() {
        assertTrue(exhibit.supports("patch"));
        assertTrue(exhibit.supports("  Delete "));
        assertTrue(!exhibit.supports("HEAD"));
        assertTrue(!exhibit.supports(null));
    }

    private static Decision bodyOf(Result<Decision> result) {
        return switch (result) {
            case Success<Decision> success -> success.value();
            case Failed<Decision> failed -> throw new AssertionError(failed.failure().message());
        };
    }
}

package guide.domain.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * InspectHttpRequest tests — the policy shell around the exhibit: the method
 * token is normalized to uppercase, blank method/path is a validation
 * failure, and (by design) nothing else can fail — an inspector tolerates
 * whatever the wire carried.
 */
class InspectHttpRequestTest {

    private final InspectHttpRequest useCase = new InspectHttpRequest(new HeadersExhibit());

    @Test
    void normalizesTheMethodTokenToUppercase() {
        Result<HeadersExhibit.InspectReport> result =
                useCase.execute("get", "/api/demo/http/inspect", null,
                        List.of(), HeadersExhibit.IncomingBody.absent());

        Success<HeadersExhibit.InspectReport> success =
                assertInstanceOf(Success.class, result);
        assertEquals("GET", success.value().method());
    }

    @Test
    void blankMethodOrPathIsAValidationFailure() {
        Result<HeadersExhibit.InspectReport> result =
                useCase.execute(" ", "/x", null, List.of(), HeadersExhibit.IncomingBody.absent());

        Failed<HeadersExhibit.InspectReport> failed = assertInstanceOf(Failed.class, result);
        assertEquals(FailureKind.VALIDATION, failed.failure().kind());
    }

    @Test
    void unknownQueryAndHeaderNamesAreToleratedNotRejected() {
        Result<HeadersExhibit.InspectReport> result =
                useCase.execute("GET", "/api/demo/http/inspect", "whatever=1&noise=x",
                        List.of(new HeadersExhibit.IncomingHeader("x-never-heard-of-it", "v")),
                        HeadersExhibit.IncomingBody.absent());

        Success<HeadersExhibit.InspectReport> success =
                assertInstanceOf(Success.class, result);
        assertEquals("whatever=1&noise=x", success.value().query());
        assertEquals(1, success.value().headers().size());
        assertFalse(success.value().headers().getFirst().known());
    }
}

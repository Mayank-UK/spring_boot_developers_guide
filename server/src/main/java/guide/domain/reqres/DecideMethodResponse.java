package guide.domain.reqres;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Use case behind the reqres methods demo (topic 09): normalizes the verb,
 * maintains the two live counters the decisions need (creations for POST's
 * Location, revisions for PUT's ETag — so repeated calls visibly progress),
 * and delegates the actual decision to the exhibit. An unsupported verb is
 * a typed validation failure; through the advice it surfaces as a 400
 * envelope, the same contract as every other failure in the app.
 */
public class DecideMethodResponse {

    private final MethodsExhibit exhibit;
    private final AtomicLong creations = new AtomicLong();
    private final AtomicLong revisions = new AtomicLong();

    public DecideMethodResponse(MethodsExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<MethodsExhibit.Decision> execute(String method) {
        String verb = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!exhibit.supports(verb)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "unsupported method: " + (method == null ? "null" : method),
                    Map.of("supported", "GET, POST, PUT, PATCH, DELETE")));
        }
        long creation = verb.equals("POST") ? creations.incrementAndGet() : 0;
        long revision = verb.equals("PUT") ? revisions.incrementAndGet() : 0;
        return Result.success(exhibit.decide(verb, creation, revision));
    }
}

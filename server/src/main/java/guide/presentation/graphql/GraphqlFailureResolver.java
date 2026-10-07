package guide.presentation.graphql;

import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.logging.RequestIdFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.lang.Nullable;

/**
 * The GraphQL world's @ControllerAdvice: resolvers throw AppException
 * (exactly as REST controllers do — the SAME taxonomy), and this adapter
 * maps it into a GraphQL error whose EXTENSIONS carry the one error
 * envelope's keys: code, requestId, plus the failure's structured details.
 * GraphQL always answers HTTP 200 with {data, errors}; this is where the
 * app decides that those errors[] entries still speak the taxonomy every
 * other protocol speaks.
 *
 * Returning null for anything else (binding problems, framework errors)
 * hands it back to spring-graphql's default handling — an unrecognized
 * exception must NOT be laundered into a fake taxonomy kind; it stays an
 * internal error, masked by the framework, logged by the service.
 */
public class GraphqlFailureResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    @Nullable
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (!(exception instanceof AppException appException)) {
            return null;
        }
        Failure failure = appException.failure();
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (failure.details() != null) {
            extensions.putAll(failure.details());
        }
        extensions.put("code", failure.kind().errorCode());
        extensions.put("requestId", currentRequestId());
        return GraphQLError.newError()
                .message(failure.message())
                .path(environment.getExecutionStepInfo().getPath())
                .extensions(extensions)
                .build();
    }

    /**
     * The request id the filter stamped — GraphQL resolutions normally run
     * on the request thread (this app's fetchers are imperative), and the
     * MDC carries it; batched fetchers may hop threads, where the honest
     * answer is "n/a" rather than a fabricated id.
     */
    private static String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId != null && !requestId.isEmpty() ? requestId : "n/a";
    }
}

package guide.core.middleware;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Topic 08's teaching interceptor: one marker in each of the three
 * HandlerInterceptor phases, so the pipeline demo can show WHERE the
 * interceptor sits relative to the filter chain and the handler:
 * preHandle after the filters but before the handler method, postHandle
 * after the handler returns, afterCompletion after the response work is
 * done — with the last two landing AFTER the handler has already built this
 * very response, which is why the demo's "current request" trace stops at
 * the handler and the previous request's ring entry shows the full unwind.
 *
 * Registered not in the servlet filter chain but in Spring MVC's interceptor
 * registry (see the wiring in MiddlewareDemoConfig) — a decision this
 * topic's handler-interceptor section unpacks: filters wrap raw servlet
 * requests; interceptors wrap mapped HANDLERS.
 */
public class PipelineTraceInterceptor implements HandlerInterceptor {

    public static final String PRE_HANDLE_MARKER = "interceptor:preHandle";
    public static final String POST_HANDLE_MARKER = "interceptor:postHandle";
    public static final String AFTER_COMPLETION_MARKER = "interceptor:afterCompletion";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        PipelineTrace.mark(request, PRE_HANDLE_MARKER);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
            @Nullable ModelAndView modelAndView) {
        PipelineTrace.mark(request, POST_HANDLE_MARKER);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            @Nullable Exception ex) {
        PipelineTrace.mark(request, AFTER_COMPLETION_MARKER);
    }
}

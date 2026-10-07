package guide.config;

import guide.core.middleware.MiddlewareOrderingFilter;
import guide.core.middleware.PipelineTraceFilter;
import guide.core.middleware.PipelineTraceInterceptor;
import guide.core.middleware.PipelineTraceLog;
import guide.data.middleware.MiddlewareRegistrationAdapter;
import guide.domain.middleware.MiddlewareRegistrationSource;
import guide.domain.middleware.ShowPipelineTrace;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Topic 08 (filters-interceptors) wiring — the composition root for this
 * app's real middleware trio, same discipline as every config class here:
 * plain classes registered as explicit @Beans, constructor injection, and
 * NOTHING below presentation knowing Spring.
 *
 * Deliberately demonstrates BOTH registration styles so the pipeline demo's
 * filter introspection can show them side by side:
 * - MiddlewareOrderingFilter: a plain @Bean Filter whose class carries
 *   @Order(20) — Boot auto-registers it and sorts it by that order.
 * - PipelineTraceFilter: an explicit FilterRegistrationBean at order 30 —
 *   the style that also owns URL patterns, name, and enabled(false) to
 *   suppress auto-registration; when a filter bean is wrapped by a
 *   registration bean, Boot registers it exactly once (through the
 *   registration).
 * The interceptor is registered NOT in the servlet filter chain but in
 * Spring MVC's interceptor registry — a WebMvcConfigurer bean, because
 * that is where handler-level cross-cutting lives.
 */
@Configuration
public class MiddlewareDemoConfig {

    /** Bounded, thread-safe ring of completed request traces — the "previous request" evidence. */
    @Bean
    PipelineTraceLog pipelineTraceLog() {
        return new PipelineTraceLog(64);
    }

    /** @Order(20) on the class + a plain @Bean: auto-registered, sorted by its annotation. */
    @Bean
    MiddlewareOrderingFilter middlewareOrderingFilter(PipelineTraceLog pipelineTraceLog) {
        return new MiddlewareOrderingFilter(pipelineTraceLog);
    }

    /** Explicit registration: order stated here, URL patterns available when needed. */
    @Bean
    FilterRegistrationBean<PipelineTraceFilter> pipelineTraceFilterRegistration() {
        FilterRegistrationBean<PipelineTraceFilter> registration =
                new FilterRegistrationBean<>(new PipelineTraceFilter());
        registration.setOrder(30);
        return registration;
    }

    /** The interceptor as a bean like any other; the registration below hands it to MVC. */
    @Bean
    PipelineTraceInterceptor pipelineTraceInterceptor() {
        return new PipelineTraceInterceptor();
    }

    /** Interceptors register with the MVC registry (path patterns are first-class here). */
    @Bean
    WebMvcConfigurer pipelineTraceInterceptorRegistration(PipelineTraceInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/api/**");
            }
        };
    }

    // ---- the demo use case ----

    @Bean
    MiddlewareRegistrationSource middlewareRegistrationSource(ApplicationContext context) {
        return new MiddlewareRegistrationAdapter(context);
    }

    @Bean
    ShowPipelineTrace showPipelineTrace(PipelineTraceLog traceLog,
            MiddlewareRegistrationSource registrationSource) {
        return new ShowPipelineTrace(traceLog, registrationSource);
    }
}

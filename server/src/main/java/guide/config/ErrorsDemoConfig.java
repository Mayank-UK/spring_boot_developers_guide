package guide.config;

import guide.domain.errors.InjectFailure;
import guide.domain.errors.ReadFailureCatalog;
import guide.domain.errors.UncaughtFailureLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Demo wiring for topic 30 (exception handling): the failure explorer's two
 * use cases, plus the process-level safety net this topic owns — the JVM
 * default uncaught-exception handler, installed as a bean side effect on
 * first creation (idempotent, so repeated context loads never chain it).
 * One @Configuration per topic keeps the composition root growing in
 * reviewable units.
 */
@Configuration
public class ErrorsDemoConfig {

    @Bean
    InjectFailure injectFailure() {
        return new InjectFailure();
    }

    @Bean
    ReadFailureCatalog readFailureCatalog(InjectFailure injectFailure) {
        return new ReadFailureCatalog(injectFailure);
    }

    /** Creating the bean installs it as the JVM-wide default (see the class). */
    @Bean
    UncaughtFailureLogger uncaughtFailureLogger() {
        return UncaughtFailureLogger.install();
    }
}

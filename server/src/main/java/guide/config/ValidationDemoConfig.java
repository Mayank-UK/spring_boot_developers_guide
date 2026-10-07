package guide.config;

import guide.domain.validation.AssessArenaSubmission;
import guide.domain.validation.ValidationArena;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Demo wiring for topic 10 (validation): the arena exhibit and its use case.
 * The jakarta {@link Validator} is the one Boot auto-configures from
 * spring-boot-starter-validation (Hibernate Validator under the interface) —
 * the same instance Spring MVC uses for {@code @Valid} request bodies, which
 * is the point: the arena runs the production machinery, not a parallel one.
 */
@Configuration
public class ValidationDemoConfig {

    @Bean
    ValidationArena validationArena(Validator validator) {
        return new ValidationArena(validator);
    }

    @Bean
    AssessArenaSubmission assessArenaSubmission(ValidationArena validationArena) {
        return new AssessArenaSubmission(validationArena);
    }
}

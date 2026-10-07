package guide.domain.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

/**
 * A custom single-field constraint: a password policy that is real code, not
 * a stack of built-ins. Writing one takes exactly two pieces — the annotation
 * (message + groups + payload, the three members every constraint must carry)
 * and a {@link ConstraintValidator} holding the actual policy.
 *
 * Two conventions worth internalizing, both demonstrated here:
 * - null PASSES: per the Bean Validation spec, most constraints treat null as
 *   "not my problem" so they compose with {@code @NotNull}/{@code @NotBlank}.
 *   The policy only judges a password that exists.
 * - the violation message NAMES THE MISSING RULES: the validator disables the
 *   static default message and builds one from what it found (or failed to).
 *   The message is built from fixed strings only — never from the submitted
 *   value, which would echo input back into responses and logs.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
public @interface StrongPassword {

    String message() default "password needs at least 10 characters including "
            + "an uppercase letter, a lowercase letter, a digit, and a symbol";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The policy: 10+ characters with upper, lower, digit, and symbol. */
    class Validator implements ConstraintValidator<StrongPassword, String> {

        private static final int MINIMUM_LENGTH = 10;

        @Override
        public boolean isValid(String password, ConstraintValidatorContext context) {
            if (password == null) {
                return true;
            }
            List<String> missing = new ArrayList<>();
            if (password.length() < MINIMUM_LENGTH) {
                missing.add("at least " + MINIMUM_LENGTH + " characters");
            }
            if (password.codePoints().noneMatch(Character::isUpperCase)) {
                missing.add("an uppercase letter");
            }
            if (password.codePoints().noneMatch(Character::isLowerCase)) {
                missing.add("a lowercase letter");
            }
            if (password.codePoints().noneMatch(Character::isDigit)) {
                missing.add("a digit");
            }
            if (password.codePoints().allMatch(Character::isLetterOrDigit)) {
                missing.add("a symbol");
            }
            if (missing.isEmpty()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "password needs " + String.join(", ", missing))
                    .addConstraintViolation();
            return false;
        }
    }
}

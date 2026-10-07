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
import java.time.LocalDate;

/**
 * A custom class-level constraint: an ordered date range across two named
 * properties. Field annotations judge one component in isolation; only a
 * constraint on the TYPE can see two components at once — which is why
 * "opens before closes", "discount before expiry", "start ≤ end" logic lives
 * here rather than as a stack of field annotations.
 *
 * The property names arrive as annotation attributes ({@code start}/{@code
 * end}), and they reappear inside the default message: Bean Validation's
 * message interpolator resolves {@code {start}} to the annotation attribute,
 * so the message stays accurate if the fields are renamed — one declaration,
 * quoted back. The validator itself reads the two properties reflectively
 * (records expose components as accessors) and applies three rules: both null
 * is a valid "no window requested"; exactly one null is a violation; both
 * present must be in order. A miswired property name (a server-side bug, not
 * the client's fault) fails loudly as IllegalStateException → 500 envelope.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DateRange.Validator.class)
public @interface DateRange {

    String message() default "range needs both {start} and {end}, {start} on or before {end}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Name of the range's opening property (a LocalDate component). */
    String start();

    /** Name of the range's closing property (a LocalDate component). */
    String end();

    /** Reads the two named properties and judges the pair as a range. */
    class Validator implements ConstraintValidator<DateRange, Object> {

        private String startProperty;
        private String endProperty;

        @Override
        public void initialize(DateRange constraint) {
            startProperty = constraint.start();
            endProperty = constraint.end();
        }

        @Override
        public boolean isValid(Object bean, ConstraintValidatorContext context) {
            if (bean == null) {
                return true;
            }
            LocalDate start = dateProperty(bean, startProperty);
            LocalDate end = dateProperty(bean, endProperty);
            if (start == null && end == null) {
                return true;
            }
            return start != null && end != null && !end.isBefore(start);
        }

        private static LocalDate dateProperty(Object bean, String name) {
            Object value;
            try {
                value = bean.getClass().getMethod(name).invoke(bean);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(
                        "@DateRange property \"" + name + "\" is not readable", error);
            }
            if (value == null || value instanceof LocalDate date) {
                return (LocalDate) value;
            }
            throw new IllegalStateException("@DateRange property \"" + name + "\" must be a LocalDate");
        }
    }
}

package guide.domain.validation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The validation arena's request payload — topic 10's teaching artifact. One
 * record exercises every validation style the topic teaches: built-in Bean
 * Validation constraints (name, email, score, eventDate), a custom
 * single-field constraint ({@link StrongPassword} — reusable policy in a
 * ConstraintValidator), and a custom class-level cross-field constraint
 * ({@link DateRange} — comparing two components no field annotation can see).
 *
 * Annotations on a domain record are unusual for this codebase (the domain
 * normally stays framework-free and validates in constructors — the topic's
 * "three layers" section is exactly about that split). This record is
 * deliberately the exception because it IS the exhibit: a wire-shaped DTO
 * whose validation contract is declared where the wire contract is declared.
 *
 * Note the two deliberately-optional fields: eventDate has only {@code @Past}
 * (null passes — most constraints skip null by spec; pair with {@code @NotNull}
 * when absence is the error), and the confirm window has NO field annotations
 * at all — its whole contract ("both dates or neither, in order") is
 * cross-field, which is what class-level constraints exist for.
 */
@DateRange(start = "confirmStart", end = "confirmEnd",
        message = "confirmation window needs both {start} and {end}, {start} on or before {end}")
public record ArenaSubmission(
        @NotBlank(message = "name is required")
        @Size(max = 40, message = "name must be at most 40 characters")
        String name,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email,

        @NotNull(message = "score is required")
        @Min(value = 0, message = "score must be at least 0")
        @Max(value = 100, message = "score must be at most 100")
        Integer score,

        @Past(message = "eventDate must be a date in the past")
        LocalDate eventDate,

        @NotBlank(message = "password is required")
        @StrongPassword
        String password,

        LocalDate confirmStart,
        LocalDate confirmEnd) {
}

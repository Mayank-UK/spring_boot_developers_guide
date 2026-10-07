package guide.domain.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import guide.core.errors.FailureKind;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The live exhibit behind topic 10's validation arena: run the FULL Bean
 * Validation machinery over one {@link ArenaSubmission} and return a
 * per-field verdict plus a preview of the error envelope a strict endpoint
 * would have answered with. Pure analysis — no Spring, no I/O, no logging.
 * The controller hands over the deserialized record; this class is the part
 * of @Valid you cannot see from the outside: {@code validator.validate(bean)}
 * is literally what Spring does after Jackson binds a {@code @Valid} body.
 *
 * The verdict never carries submitted VALUES — only classifications and the
 * constraint messages. A password is judged ("policy violations"), never
 * echoed: input is hostile, and responses/logs leak secrets just as surely
 * as databases do.
 */
public final class ValidationArena {

    /** The row label the class-level (@DateRange) verdict travels under. */
    public static final String CLASS_LEVEL_FIELD = "confirm window (confirmStart, confirmEnd)";

    private static final List<String> FIELDS = fieldNames();

    private final Validator validator;

    public ValidationArena(Validator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Assesses one submission: all violations grouped per field (class-level
     * ones in their own row), each field classified, and — when anything
     * failed — the exact 400 envelope the strict endpoint emits for the same
     * payload (same code, message, and first-message-per-field mapping as
     * the global handler's MethodArgumentNotValidException branch).
     */
    public ArenaVerdict assess(ArenaSubmission submission, String requestId) {
        Objects.requireNonNull(submission, "submission");
        Set<ConstraintViolation<ArenaSubmission>> violations = validator.validate(submission);

        Map<String, List<Violation>> byField = new LinkedHashMap<>();
        List<Violation> classLevel = new ArrayList<>();
        for (ConstraintViolation<ArenaSubmission> violation : violations) {
            String field = violation.getPropertyPath().toString();
            Violation view = new Violation(
                    violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                    violation.getMessage());
            if (field.isEmpty()) {
                classLevel.add(view);
            } else {
                byField.computeIfAbsent(field, ignored -> new ArrayList<>()).add(view);
            }
        }

        List<FieldVerdict> perField = new ArrayList<>();
        for (String field : FIELDS) {
            List<Violation> fieldViolations = byField.getOrDefault(field, List.of());
            perField.add(new FieldVerdict(field, classify(fieldViolations), fieldViolations));
        }
        if (!classLevel.isEmpty()) {
            perField.add(new FieldVerdict(CLASS_LEVEL_FIELD, "cross-field: confirmStart ≤ confirmEnd",
                    List.copyOf(classLevel)));
        }

        boolean accepted = violations.isEmpty();
        return new ArenaVerdict(accepted, List.copyOf(perField),
                accepted ? null : strictEnvelope(byField, requestId));
    }

    /**
     * Maps a field's violations to the class of thing wrong with the value:
     * the most INFORMATIVE violation wins — a blank password that also fails
     * the policy is "policy violations" (existence is listed among the
     * violations anyway), and pure existence failures say what they are.
     */
    private static String classify(List<Violation> violations) {
        if (violations.isEmpty()) {
            return "accepted";
        }
        return violations.stream().map(Violation::annotation)
                .filter(annotation -> !"NotNull".equals(annotation) && !"NotBlank".equals(annotation))
                .findFirst()
                .map(ValidationArena::classifyAnnotation)
                .orElseGet(() -> "NotNull".equals(violations.getFirst().annotation())
                        ? "missing"
                        : "missing or blank");
    }

    private static String classifyAnnotation(String annotation) {
        return switch (annotation) {
            case "Size" -> "length out of range";
            case "Min", "Max" -> "outside numeric range";
            case "Email" -> "not an email address";
            case "Past" -> "date not in the past";
            case "StrongPassword" -> "policy violations";
            default -> "violated";
        };
    }

    /**
     * The envelope preview, built from the same violations: the global
     * handler's mapping — first message per field, class-level excluded
     * (those are global errors, not field errors) — so the demo's preview and
     * the strict endpoint's real answer cannot drift apart in shape.
     */
    private static Map<String, Object> strictEnvelope(Map<String, List<Violation>> byField,
            String requestId) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, List<Violation>> entry : byField.entrySet()) {
            fields.putIfAbsent(entry.getKey(), entry.getValue().getFirst().message());
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("fields", fields);
        error.put("code", FailureKind.VALIDATION.errorCode());
        error.put("message", "request body failed validation");
        error.put("requestId", requestId);
        return Map.of("error", error);
    }

    /** The record's component names, in declaration order — one source of truth. */
    private static List<String> fieldNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : ArenaSubmission.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return List.copyOf(names);
    }

    /** One constraint violation, as plain data: the annotation + its message. */
    public record Violation(String annotation, String message) {
    }

    /** One field's verdict: classification of the value plus its violations. */
    public record FieldVerdict(String field, String valueClassification, List<Violation> violations) {

        public FieldVerdict {
            violations = List.copyOf(violations);
        }
    }

    /** The arena's answer: accepted or not, per-field analysis, envelope preview. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArenaVerdict(boolean accepted, List<FieldVerdict> perField,
            Map<String, Object> envelopeComparison) {

        public ArenaVerdict {
            perField = List.copyOf(perField);
        }
    }
}

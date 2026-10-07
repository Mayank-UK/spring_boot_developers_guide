package guide.domain.i18n;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The greeting endpoint's boundary judge — the topic 10 / topic 11 stance
 * applied to i18n: parse and validate EVERYTHING at once, answer a single
 * typed Failure naming each offending field, never partially act. The
 * useCase switch doubles as the section index into the bundles: each case
 * exercises a different MessageSource behavior (parameters, dates, choice
 * patterns, fallback, absence, pseudo-localization).
 */
public record GreetingRequest(UseCase useCase, String name, int count) {

    /** Which bundle behavior the greeting endpoint demonstrates. */
    public enum UseCase {
        WELCOME("welcome"),
        RETURNING("returning"),
        PLURAL("plural"),
        FALLBACK("fallback"),
        MISSING("missing"),
        PSEUDO("pseudo");

        private final String wireName;

        UseCase(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** Absent means WELCOME; anything else must name a case exactly. */
        static Result<UseCase> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Result.success(WELCOME);
            }
            for (UseCase useCase : values()) {
                if (useCase.wireName.equals(raw.strip().toLowerCase(Locale.ROOT))) {
                    return Result.success(useCase);
                }
            }
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "unknown useCase", Map.of("fields", Map.of(
                            "useCase", "must be one of " + names()))));
        }

        static List<String> names() {
            return java.util.Arrays.stream(values()).map(UseCase::wireName).toList();
        }
    }

    /** The default name interpolated into {0} when the caller sends none. */
    public static final String DEFAULT_NAME = "developer";

    public static final int DEFAULT_COUNT = 5;

    /** The count ceiling — choice patterns render the number verbatim. */
    public static final int MAX_COUNT = 1000;

    /** Judges the raw query parameters; null/blank fields take defaults. */
    public static Result<GreetingRequest> of(String useCase, String name, Integer count) {
        Result<UseCase> parsedUseCase = UseCase.parse(useCase);
        if (parsedUseCase instanceof Failed<UseCase> failed) {
            // Re-wrap: a Failed<UseCase> is not a Failed<GreetingRequest>.
            return Result.failed(failed.failure());
        }
        Map<String, String> problems = new java.util.LinkedHashMap<>();
        String resolvedName = (name == null || name.isBlank())
                ? DEFAULT_NAME
                : name.strip();
        if (resolvedName.length() > 40) {
            problems.put("name", "must be at most 40 characters");
        }
        int resolvedCount = count == null ? DEFAULT_COUNT : count;
        if (resolvedCount < 0 || resolvedCount > MAX_COUNT) {
            problems.put("count", "must be within 0.." + MAX_COUNT);
        }
        if (!problems.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "request parameters failed validation", Map.of("fields", problems)));
        }
        UseCase chosen = parsedUseCase instanceof Success<UseCase> success
                ? success.value()
                : UseCase.WELCOME;
        return Result.success(new GreetingRequest(chosen, resolvedName, resolvedCount));
    }
}

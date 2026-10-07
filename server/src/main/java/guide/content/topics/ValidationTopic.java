package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 10 — Validation. Group middleware-request, order 4. The boundary
 * discipline: why input is judged where it enters, the three layers that
 * judge it (schema, application, domain invariants), hand-rolled record
 * constructors vs Bean Validation annotations, custom and class-level
 * constraints (written for real in guide/domain/validation and exercised by
 * the live validation:arena demo), and the outbound half — DTOs, Jackson
 * views, and the client's own mirror of the same discipline.
 */
public final class ValidationTopic {

    private ValidationTopic() {
    }

    public static Topic topic() {
        return new Topic("validation", "Validation",
                "Input is hostile until validated: the three layers that judge it "
                        + "(schema, Bean Validation at the edge, invariants in the domain), "
                        + "hand-rolled record constructors, custom and class-level constraints, "
                        + "and the outbound mirror — DTOs, views, and field whitelists.",
                TopicGroupId.MIDDLEWARE_REQUEST, 4,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-validate-at-the-boundary", "Why validate at the boundary",
                        "Every request is an attack until proven otherwise — and the only place you can afford to judge it is where it enters.",
                        text("boundary:hostile", """
                                The premise first, because every design decision in this topic falls out of it: INPUT IS HOSTILE. Not because users are evil — most are not — but because your HTTP endpoint is reachable by anyone who can type a URL, and by every script that found it in a crawl. A request body is not "the fields my frontend sends"; it is arbitrary bytes that happen to parse as JSON today. The missing field, the 10-megabyte string, the score of -9999, the date that ends before it begins, the password that is "password": none of these are exceptions — they are Tuesday. The quality principles this repo states it as defensive programming; the OWASP API Security Top 10 (security topic) ranks property-level validation failures among the most common real-world API flaws.

                                Validation is the act of deciding whether input may enter the system at all. It is worth separating from two neighbors it is often blurred with. SANITATION changes input to make it safe — escaping HTML entities, stripping control characters — and answers "how do I store this safely?", not "may I accept this?". NORMALIZATION canonicalizes accepted input — trimming whitespace, lowercasing an email, rounding a timestamp to UTC — so that " Ada@Example.COM " and "ada@example.com" become one identity. The order matters: validate first (reject what is unacceptable), then normalize (make the acceptable canonical), and sanitize for the context where the value will be RENDERED (the XSS story belongs to output encoding, not to input rejection). A validator that quietly rewrites what it was given is doing three jobs badly."""),
                        text("boundary:dto-in-domain-out", """
                                The boundary contract this app holds: a wire-shaped DTO in, a clean domain object out. Jackson binds JSON into a record shaped like the wire (strings, nullable, unconstrained); validation judges THAT record at the edge; only then does a use case receive values it can trust — or the request is refused before any business logic runs. Past the boundary, domain code stops defending itself against "what if score is null": it cannot be, because nothing null gets past the door. That is the payoff of boundary validation — not the act of checking, but the license it hands to everything behind it.

                                Two properties make a good boundary check. FAIL-FAST: reject the whole request on the first pass over it, before any side effect — no partial writes that must be rolled back because field nine of twelve was invalid. And TYPED ERRORS: a validation failure is not an exception-shaped mystery; it is one FailureKind in this app's taxonomy — VALIDATION, HTTP 400, error code validation_failed — rendered by the global handler into the same envelope every other failure uses. The client you will meet at the end of this topic does the mirror-image check on data flowing the other way, and for the same reason: the network is not a type system, no matter what the frontend's interface declarations promise.""")),
                section("the-three-layers", "The three layers of validation",
                        "Schema, application, and domain — three nets at different depths, each catching what the previous could not.",
                        text("layers:map", """
                                Validation is not one check; it is three nets set at different depths, and each exists because the others leak. The TRANSPORT/SCHEMA layer is the contract's paperwork: an OpenAPI document (the api-documentation topic generates one from this very code) that declares which fields exist, their types, and which are required — so clients and tooling can fail before a byte is sent, and code generators can bake in stub checks. It cannot express "this email is not already registered" or "end after start"; it is a net with big holes, deliberately.

                                The APPLICATION layer is Bean Validation at the controller boundary — the annotated DTO judged by the framework the moment Jackson finishes binding. @NotNull, @Size, @Email: this is where per-field, declarative, wire-shaped rules live, and it is the layer the rest of this topic dissects. It runs before your code does, costs a few annotations per field, and catches shape violations cheaply.

                                The DOMAIN layer is invariants enforced where the object is born: constructors and factory methods that simply refuse to build an invalid instance. Defense in depth is the explicit rationale — a use case invoked from a second controller, a message consumer, a batch job, or a test bypasses the controller boundary entirely; nothing bypasses a constructor. This app's own content registry is the canonical in-repo example: it validates at construction, so the Spring context cannot even boot with broken content — duplicate ids, blank ids, and unknown groups all fail loudly at startup, before any request arrives:"""),
                        code("layers:registry", "java",
                                "guide/content/ContentRegistry.java — the domain net: the registry refuses to exist with duplicate or blank ids",
                                "server/src/main/java/guide/content/ContentRegistry.java", """
                                private static void validate(List<Topic> list) {
                                    Set<String> seenTopicIds = new HashSet<>();
                                    for (Topic topic : list) {
                                        Objects.requireNonNull(topic, "topic");
                                        requireNonBlank(topic.id(), "topic id");
                                        if (!seenTopicIds.add(topic.id())) {
                                            throw new IllegalStateException(
                                                    "content registry: duplicate topic id \\"" + topic.id() + "\\"");
                                        }
                                        // …
                                    }
                                }"""),
                        text("layers:who-runs-what", """
                                Which layer owns which rule? The test is always: where would the rule still be true if the transport changed? "name fits in 40 characters" is a storage/wire concern — schema or DTO annotations. "a topic id is unique within the registry" is a content-model invariant — the domain, no matter who calls. "the confirmation window ends after it starts" sits in between: it is about the meaning of the data, so this topic puts it on the DTO as a class-level constraint (the wire is where the two dates first meet) while noting the domain-purist alternative — a ConfirmationWindow value type whose constructor enforces the order, making an invalid window UNREPRESENTABLE rather than rejected.

                                The application layer's mechanics in this repo are one annotation deep: @Valid on the @RequestBody parameter of a strict endpoint. Spring MVC binds the body, hands it to the configured validator (the same Hibernate Validator instance the arena demo uses programmatically), and on failure throws MethodArgumentNotValidException before the controller method body runs — which is why the controller below contains zero if-statements about nulls:"""),
                        code("layers:strict", "java",
                                "guide/presentation/demo/ValidationDemoController.java — the application net: @Valid is the whole wiring",
                                "server/src/main/java/guide/presentation/demo/ValidationDemoController.java", """
                                /** The production default: @Valid ⇒ violations become the standard 400 envelope. */
                                @PostMapping(value = "/api/demo/validation/arena/strict",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                ValidationArena.ArenaVerdict strict(@Valid @RequestBody ArenaSubmission submission,
                                        HttpServletRequest request) {
                                    return unwrap(assessArenaSubmission.execute(submission, requestId(request)));
                                }"""),
                        text("layers:division-of-labor", """
                                Notice the division of labor the three layers produce when they cooperate. The schema catches type nonsense before the network. Bean Validation catches shape violations at the edge, with messages the client can show next to form fields. The domain catches everything the first two cannot see — cross-aggregate rules, state-dependent rules — and, crucially, protects INTERNAL callers too. What no layer does is trust another layer completely: the schema does not know the validator ran, the validator does not know the constructor checks. Redundant checks at different depths are not waste; they are the reason a bug in one net is a log line, not a data corruption incident.""")),
                section("hand-rolled-validation", "Hand-rolled validation: records that refuse",
                        "A record's compact constructor is a validator you already own — no framework, no annotations, one throw.",
                        text("hand:compact", """
                                Java records give you a validation hook built into the language: the COMPACT CONSTRUCTOR, which runs between argument passing and field assignment for every creation path — constructor, Jackson binding, reflection. Its teaching shape is three lines: check the invariant, throw on breach, otherwise let the canonical constructor finish. The whole types-and-nulls discipline of the java-language topic concentrates here: a record whose constructor refuses nonsense makes its invalid states unrepresentable, and every holder of that type inherits the guarantee for free. This app's domain records use it everywhere; the smallest honest example is a header pair that refuses to exist without both halves:"""),
                        code("hand:incoming", "java",
                                "guide/domain/http/HeadersExhibit.java — a compact constructor as a validator: null parts are a creation error",
                                "server/src/main/java/guide/domain/http/HeadersExhibit.java", """
                                /** One request header as received, before classification. */
                                public record IncomingHeader(String name, String value) {

                                    public IncomingHeader {
                                        Objects.requireNonNull(name, "name");
                                        Objects.requireNonNull(value, "value");
                                    }
                                }"""),
                        text("hand:why-it-works", """
                                Why this works so well with records specifically: records have no setters, so the constructor is the ONLY door — validate once at the door and the object is immutable and trustworthy for life. Contrast a JavaBean: sixteen possible mutation points after construction, each needing its own re-check, which is exactly why JavaBean validation grew into a framework. The registry's blank-id check shows the same pattern with a semantic (not just nullity) rule, and the exception TYPE is chosen deliberately — IllegalStateException says "the program's state is inconsistent" (a bug), where a validator at the HTTP edge would raise a 400 ValidationException (the caller's fault):"""),
                        code("hand:nonblank", "java",
                                "guide/content/ContentRegistry.java — a semantic invariant: blank ids cannot reach the registry's state",
                                "server/src/main/java/guide/content/ContentRegistry.java", """
                                private static void requireNonBlank(String value, String what) {
                                    if (value == null || value.isBlank()) {
                                        throw new IllegalStateException("content registry: " + what + " must not be blank");
                                    }
                                }"""),
                        text("hand:pros-cons", """
                                The honest pros and cons versus the annotation machinery of the next sections. PROS: zero dependencies (the domain stays framework-free — the dependency rule in this repo's architecture is satisfied by construction); the checks are ordinary code, so they can express ANY rule (cross-field, state-dependent, I/O-touching if you insist) and they are trivially unit-testable by just calling the constructor; the failure is a normal Java exception with your message and your type, not a framework-managed violation set. CONS: nothing drives them automatically at the HTTP edge — a controller must catch and map them itself (or, in this app, let the global handler's 500 branch have them, which is why hand-rolled checks belong BEHIND the boundary, not on it); no per-field violation bundle for the client, just one throw; and every rule is imperative code that must be discovered by reading, where an annotation reads as a declarative contract.

                                The teaching point is not that one style wins: it is that NOT EVERYTHING NEEDS A FRAMEWORK. A required-pair invariant is three lines of record; reaching for @NotNull on a domain type would buy you nothing but a jakarta import in your pure domain. Conversely, judging an arbitrary wire payload field-by-field with hand-written ifs is a maintenance treadmill the next section retires.""")),
                section("bean-validation-annotations", "Bean Validation: the annotation catalog",
                        "Jakarta Validation's declarative catalog on one DTO — @Valid triggers it, the advice maps it to the 400 envelope.",
                        text("bean:catalog", """
                                Bean Validation (jakarta.validation, implemented by Hibernate Validator, auto-configured by spring-boot-starter-validation) turns per-field rules into annotations on the DTO's components. The catalog you will reach for constantly: @NotNull rejects null (but accepts blank — pair with @NotBlank for strings); @NotBlank rejects null AND whitespace-only (strings only); @Size(min/max) bounds string or collection length; @Min/@Max bound numbers inclusively; @DecimalMin/@DecimalMax do the same for decimals with precision control; @Email demands an address-shaped string; @Pattern(regexp) demands a regex match (and PRECOMPILE your pattern as a constant if you also check it by hand — the regex topic's lesson); @Past/@PastOrPresent/@Future/@FutureOrPresent judge temporals (null passes!); @Positive/@PositiveOrZero/@Negative reject wrong-signed numbers; @AssertTrue is a boolean you compute. One spec-level convention to internalize NOW: EVERY built-in except the Not-blank/not-null family treats null as VALID — the constraints judge VALUES, existence is @NotNull's separate job. This app's arena DTO is the catalog on one record — it is the demo payload you will be breaking all topic:"""),
                        code("bean:dto", "java",
                                "guide/domain/validation/ArenaSubmission.java — the whole catalog on one record: built-ins, a custom constraint, a class-level one",
                                "server/src/main/java/guide/domain/validation/ArenaSubmission.java", """
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
                                }"""),
                        text("bean:valid-and-advice", """
                                Annotations declare; @Valid triggers. Put @Valid on a @RequestBody parameter and Spring MVC validates the bound object before your method runs, throwing MethodArgumentNotValidException on failure — the violations (as field errors) ride the exception to the @ControllerAdvice, whose branch for it is now part of this app's global handler. Note what the handler chooses to put on the wire and what it keeps off the log: the response's fields map carries the first message per field (exactly what a form UI wants to render), while the log line records field NAMES only — one of those fields is password, and messages-plus-rejected-values have no business in logs. (@Valid on @RequestBody parameters only — for cascading INTO nested objects use @Valid on the nested field; for service-method parameters, method validation is its own mechanism with its own exception.)"""),
                        code("bean:handler", "java",
                                "guide/presentation/GlobalExceptionHandler.java — MethodArgumentNotValidException becomes the standard 400 envelope, first message per field",
                                "server/src/main/java/guide/presentation/GlobalExceptionHandler.java", """
                                @ExceptionHandler(MethodArgumentNotValidException.class)
                                ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException exception,
                                        HttpServletRequest request) {
                                    String requestId = requestId(request);
                                    Map<String, String> fieldErrors = new LinkedHashMap<>();
                                    for (FieldError error : exception.getBindingResult().getFieldErrors()) {
                                        fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
                                    }
                                    // …
                                    return ResponseEntity.badRequest()
                                            .body(ErrorResponse.of(FailureKind.VALIDATION.errorCode(),
                                                    "request body failed validation", requestId, Map.of("fields", fieldErrors)));
                                }"""),
                        code("bean:wire", "http", "what the client actually receives — one envelope, machine-checkable fields, a request id", null, """
                                HTTP/1.1 400
                                Content-Type: application/json
                                x-request-id: demo-42

                                {"error":{"fields":{"name":"name is required","score":"score must be at most 100"},
                                 "code":"validation_failed","message":"request body failed validation",
                                 "requestId":"demo-42"}}"""),
                        text("bean:groups", """
                                Survey: GROUPS and VALIDATION SEQUENCES, for completeness — real, occasionally load-bearing, usually overkill. Every constraint annotation carries an invisible groups() attribute; @Validated(SomeGroup.class) on a type or parameter runs ONLY constraints declared for that group, so one DTO can serve two callers (Create wants the id absent, Update wants it present). The Default group is what plain @Valid runs. Sequences (@GroupSequence) order groups — run cheap NotNull checks before the expensive @Email-format or database-touching checks, and stop at the first failing group. The textbook caution applies: more than two groups on one DTO is usually a sign you want two DTOs — sharing a class while diverging its contract gives you neither's clarity. When you genuinely need "different rules per caller," two thin records mapping to one domain type read better than one record with a group matrix.""")),
                section("custom-constraints", "Custom constraints: policy as an annotation",
                        "When the catalog runs out, you write the annotation yourself — @StrongPassword, for real, in this repo.",
                        text("custom:when", """
                                The catalog ends where POLICY begins — a rule too specific to be standard ("passwords need all four character classes"), or named in your domain's language (@Iban, @SkuCode, @NoControlChars). Bean Validation's extension point is deliberately tiny: an annotation plus a ConstraintValidator, and your rule becomes indistinguishable from the built-ins — same declaration site, same groups, same message interpolation, same discovery by @Valid. This topic's arena needs a password policy, so this repo contains a real one. The annotation half carries the three members EVERY constraint must declare (message, groups, payload — the spec requires them, default values make them invisible) plus the link to its validator:"""),
                        code("custom:annotation", "java",
                                "guide/domain/validation/StrongPassword.java — the annotation half: message/groups/payload + the validator link",
                                "server/src/main/java/guide/domain/validation/StrongPassword.java", """
                                @Documented
                                @Target(ElementType.FIELD)
                                @Retention(RetentionPolicy.RUNTIME)
                                @Constraint(validatedBy = StrongPassword.Validator.class)
                                public @interface StrongPassword {

                                    String message() default "password needs at least 10 characters including "
                                            + "an uppercase letter, a lowercase letter, a digit, and a symbol";

                                    Class<?>[] groups() default {};

                                    Class<? extends Payload>[] payload() default {};
                                }"""),
                        text("custom:validator", """
                                The validator half is plain code with two rules of the road. First: null PASSES — the spec assigns existence-checking to @NotNull/@NotBlank, so custom constraints stay composable with them ("required" is a different concern than "well-formed"). Second: the message can be DYNAMIC — the validator can disable the static default and build a violation message from what it found. That is where @StrongPassword's usefulness lives: "password needs an uppercase letter, a symbol" tells the user what to fix; a generic "invalid password" makes them guess. The message is assembled from fixed strings only, never from the submitted value — echoing input into responses is how secrets leak and how injection payloads travel; the arena's verdict table classifies the password without ever repeating it:"""),
                        code("custom:validator-impl", "java",
                                "guide/domain/validation/StrongPassword.java — the policy half: null passes, and the violation names the missing rules",
                                "server/src/main/java/guide/domain/validation/StrongPassword.java", """
                                @Override
                                public boolean isValid(String password, ConstraintValidatorContext context) {
                                    if (password == null) {
                                        return true;
                                    }
                                    List<String> missing = new ArrayList<>();
                                    // …
                                    if (missing.isEmpty()) {
                                        return true;
                                    }
                                    context.disableDefaultConstraintViolation();
                                    context.buildConstraintViolationWithTemplate(
                                            "password needs " + String.join(", ", missing))
                                            .addConstraintViolation();
                                    return false;
                                }"""),
                        text("custom:interpolation", """
                                MESSAGE INTERPOLATION, surveyed by way of what this one file uses. The default message resolves {jakarta.validation.constraints.Size.message}-style keys from the provider's bundle, and YOUR messages resolve annotation attributes: @DateRange's default message in the next section literally contains {start} and {end}, resolved to the annotation's own attribute values at violation time. The dynamically built template here goes through the same interpolator, which is why it contains no braces — user-influenced text must never reach a template (an attacker who can write ${...} into a message template can execute expression language on some stacks; keep templates fixed strings and put data in attributes). And because messages are lookups, they localize: swap the ValidationMessages bundle and the SAME constraint speaks German — the internationalization topic's bridge.""")),
                section("class-level-and-composed", "Class-level and composed constraints",
                        "Field annotations cannot see siblings. Class-level constraints judge the whole object; composition stacks annotations into new ones.",
                        text("class:cross-field", """
                                Some rules are about the SPACE BETWEEN fields: end after start, discount before expiry, exactly one of phone/email. No field annotation can express them, because a field constraint sees exactly one component. Bean Validation's answer is the class-level constraint — @Target(ElementType.TYPE), a validator that receives the WHOLE object and reads whichever properties it needs. The arena's confirmation window is the canonical case: confirmStart and confirmEnd are individually unconstrained (either may be null — "no window requested" is legitimate), yet the PAIR must arrive "both or neither, in order." That is three rules about two fields, declared once, on the type:"""),
                        code("class:annotation", "java",
                                "guide/domain/validation/DateRange.java — a reusable class-level constraint parameterized by property names",
                                "server/src/main/java/guide/domain/validation/DateRange.java", """
                                @Documented
                                @Target(ElementType.TYPE)
                                @Retention(RetentionPolicy.RUNTIME)
                                @Constraint(validatedBy = DateRange.Validator.class)
                                public @interface DateRange {

                                    String message() default "range needs both {start} and {end}, {start} on or before {end}";

                                    Class<?>[] groups() default {};

                                    Class<? extends Payload>[] payload() default {};

                                    String start();

                                    String end();
                                }"""),
                        code("class:validator", "java",
                                "guide/domain/validation/DateRange.java — the pair judged: both-null passes, one-null fails, inverted fails",
                                "server/src/main/java/guide/domain/validation/DateRange.java", """
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
                                }"""),
                        text("class:composed", """
                                COMPOSITION is the other stacking trick: an annotation whose own declaration carries constraints (meta-annotated, the way @StrongPassword would read if it were @NotBlank-plus-@Size-plus-@Pattern glued with @ConstraintComposition(AND)) — stack existing rules into a named one, and every use site inherits the stack. It is the right tool when your policy IS a conjunction of catalog rules; this repo's password policy chose a ConstraintValidator instead, for the one thing composition cannot do: compute a MESSAGE from what failed (a composed violation reports each layer's static message, not "you are missing these two classes"). Rule of thumb: composition for bundling, a validator for judgment.

                                The design question worth pausing on: when does cross-field logic deserve a CLASS-LEVEL CONSTRAINT, and when a FACTORY METHOD in the domain? The constraint version keeps the wire DTO flat and the rule visible on the type — right for input shaped by forms and clients you do not control. The factory version makes the invalid state UNREPRESENTABLE: a ConfirmationWindow.of(start, end) that throws (or returns a Result) means downstream code holds a TYPE that carries its own guarantee — stronger than any annotation, because it holds for every caller forever. This app's split: annotations judge the wire at the boundary (this topic); compact constructors and value types guard the domain (the layering the architecture topic formalizes). Both exist here on purpose — the arena demonstrates the first while literally being an instance of the second discipline.""")),
                section("choosing-between-them", "Choosing between them",
                        "Hand-rolled invariants, declarative annotations, custom constraints — the decision framework, and the arena that settles arguments.",
                        text("choose:framework", """
                                The decision framework, compressed to three questions. WHERE DOES THE RULE LIVE? On the wire (shape, presence, format) → Bean Validation annotations on the DTO; on the domain's meaning (uniqueness, invariants, cross-aggregate truth) → constructors and value types that refuse invalid state. IS THE RULE REUSABLE POLICY? A rule you would name in a design review — password policy, SKU shape, IBAN — earns a custom constraint: annotation plus validator, composable everywhere the policy applies; a rule true of one DTO in one controller is three throwaway annotations or a compact-constructor check. WHO NEEDS TO KNOW? If the client needs per-field messages to render a form, you want the validator's violation set; if the failure is "this cannot exist," one typed throw says it better.

                                The arena demo drives the framework live, both philosophies on one payload. Its analyst endpoint binds WITHOUT @Valid and runs the same machinery programmatically — validator.validate(bean) is exactly what Spring does behind @Valid, made visible:"""),
                        code("choose:arena", "java",
                                "guide/domain/validation/ValidationArena.java — the machinery Spring hides: validate() returns a violation set you can analyze",
                                "server/src/main/java/guide/domain/validation/ValidationArena.java", """
                                public ArenaVerdict assess(ArenaSubmission submission, String requestId) {
                                    Objects.requireNonNull(submission, "submission");
                                    Set<ConstraintViolation<ArenaSubmission>> violations = validator.validate(submission);
                                    // …
                                    boolean accepted = violations.isEmpty();
                                    return new ArenaVerdict(accepted, List.copyOf(perField),
                                            accepted ? null : strictEnvelope(byField, requestId));
                                }"""),
                        text("choose:two-endpoints", """
                                The strict sibling endpoint is the same DTO behind @Valid — violations throw out to the advice and the client gets the standard 400 envelope. Identical constraints, identical validator, one difference: WHO DRIVES. That is the whole decision in miniature — when judgment is the product (form validation UI, API diagnostics, a linter), drive the machinery yourself and answer richly; when rejection is routine (every production write path), let @Valid throw and let the one envelope speak. The verdict's second half — envelopeComparison — closes the loop: for a rejected payload it shows the exact envelope the strict endpoint emitted for the same bytes, so you can watch both philosophies answer one request:"""),
                        code("choose:analyst", "java",
                                "guide/presentation/demo/ValidationDemoController.java — the analyst endpoint: no @Valid, never rejects, always explains",
                                "server/src/main/java/guide/presentation/demo/ValidationDemoController.java", """
                                /** The analyst: never rejects — judges. Returns the verdict + envelope preview. */
                                @PostMapping(value = "/api/demo/validation/arena",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                ValidationArena.ArenaVerdict arena(@RequestBody ArenaSubmission submission,
                                        HttpServletRequest request) {
                                    return unwrap(assessArenaSubmission.execute(submission, requestId(request)));
                                }"""),
                        demo("choose:demo", "validation:arena", "The validation arena, live",
                                "Edit the ArenaSubmission payload — break the name, the email, the score, the date, the password policy, the confirm window — and submit it to both endpoints. The analyst answers 200 with a per-field verdict table (annotation, message, value classification, class-level @DateRange in its own row) plus the exact 400 error envelope the strict endpoint returns for the identical payload. Submitted passwords are classified, never echoed.")),
                section("serialization-jackson-out", "Serialization: the outbound boundary (Jackson)",
                        "Validation guards the door in; deliberate mapping guards it out — DTO ≠ entity, views, and the client's whitelist mirror.",
                        text("out:dto-not-entity", """
                                Validation is half the boundary story; the outbound half is SERIALIZATION, and its first rule: a response DTO is not your entity wearing a JSON hat. Mapping out is a DESIGN ACT: you choose which fields exist, which are null-omitted, which shapes the client actually consumes. Serve the entity raw and every internal field, lazy association, and refactor becomes a wire break — the api-design topic calls this the contract freeze; here the mechanism is simply: separate output records, mapped by hand, one deliberate line per field. This app never serializes its Topic record to list views — it serves a TopicSummary with a sectionCount instead of the sections payload; the mapping function is four lines and they ARE the contract:"""),
                        code("out:summary", "java",
                                "guide/content/TopicSummary.java — the deliberate map-out: what list views see, and nothing they do not",
                                "server/src/main/java/guide/content/TopicSummary.java", """
                                /** A topic without its sections payload — what list endpoints serve. */
                                @JsonInclude(JsonInclude.Include.NON_NULL)
                                public record TopicSummary(String id, String title, String subtitle, TopicGroupId group, int order,
                                        int sectionCount) {

                                    public static TopicSummary from(Topic topic) {
                                        return new TopicSummary(topic.id(), topic.title(), topic.subtitle(), topic.group(),
                                                topic.order(), topic.sections().size());
                                    }
                                }"""),
                        text("out:records-on-wire", """
                                RECORDS ON THE WIRE behave exactly as the immutability story predicts: Jackson (3 in Boot 4 — tools.jackson databind, com.fasterxml annotations) serializes components in declaration order via the accessors; there are no setters, so deserialization goes through the canonical constructor — which is why a compact-constructor invariant ALSO guards your input path even when you skip @Valid. Field order is declaration order (readable diffs, stable clients); @JsonInclude(NON_NULL) drops absent components instead of shipping null noise — visible on TopicSummary above; and when the wire name must differ from the Java name (or a type needs a stable discriminator), annotations on the accessor say so explicitly, as this repo's content blocks do to serialize kind() as \"kind\":"""),
                        code("out:kind", "java",
                                "guide/content/CodeBlock.java — naming the wire explicitly when Java and JSON must disagree",
                                "server/src/main/java/guide/content/CodeBlock.java", """
                                @JsonProperty("kind")
                                @Override
                                public String kind() {
                                    return "code";
                                }"""),
                        text("out:views", """
                                Survey: JACKSON VIEWS (@JsonView) — one type, several serialization faces. Mark fields with @JsonView(Public.class)/@JsonView(Internal.class) marker interfaces, then activate per endpoint: @JsonView(Public.class) on the handler (or spring.jackson.default-view-inclusion for the fallback set). Same object, public endpoint leaks nothing internal, admin endpoint sees all. When to reach for it: a genuinely shared shape with two audiences and one source of truth. When NOT: views are invisible in the type — a field's exposure is decided at the mapping site, not the record, so they quietly rot; SEPARATE OUTPUT DTOs (the TopicSummary pattern) put the contract in the type system and cost four lines each. This repo chooses output records everywhere; the whitelist discipline matters more than which mechanism, because the failure mode is identical in both: an entity served raw today is a leaked field tomorrow. Same-family survey: @JsonIgnore for one-off exclusion, mixins for annotating types you cannot edit, @JsonUnwrapped for flattening — all tools for the same deliberate-mapping job."""),
                        text("out:client-mirror", """
                                And the client runs the SAME discipline in reverse — inbound at its edge, which is the last piece of the boundary picture. Every DTO this React client parses goes through hand-rolled whitelist validators: throw on shape violation, copy ONLY the declared fields (unknown wire keys are dropped, not passed through), and surface bad server data loudly instead of letting it reach the domain as NaN orders or \"[object Object]\" titles. The server validates what the client sends; the client validates what the server sends; neither trusts the wire. The mirror, verbatim from the client's data layer:"""),
                        code("out:client-validate", "ts",
                                "client/src/data/content/validate.ts — the client's half of the boundary: whitelist + throw, never coerce",
                                "client/src/data/content/validate.ts", """
                                export function requiredString(source: Record<string, unknown>, key: string): string {
                                  const value = source[key];
                                  if (typeof value !== 'string' || value === '') {
                                    throw new Error(`expected string at "${key}"`);
                                  }
                                  return value;
                                }""")),
                section("terminal-companion", "The boundary from a terminal",
                        "curl is the hostile client: a valid payload, every violation class, and the envelopes that answer them.",
                        text("term:tour", """
                                The terminal is where the hostile-input premise becomes tangible: curl does not run your form validation, does not respect your types, and will happily send a score of ten thousand. The tour below hits both endpoints of the arena — the analyst (200 verdict + envelope preview) and the strict twin (the real 400 envelope) — then steps through each violation class one payload at a time, and ends with a body that never parses. Watch the two shapes stay in lockstep: the preview the analyst returns for a payload is the envelope the strict endpoint emits for the same bytes. Start the server (docker compose up, or run GuideApplication), then take the tour:"""),
                        code("term:valid", "bash", "a valid payload — accepted by both endpoints, no envelope to compare", null, """
                                # Both endpoints accept a well-formed submission:
                                curl -s -X POST localhost:8080/api/demo/validation/arena \\
                                  -H 'Content-Type: application/json' -d '{
                                    "name":"Ada Lovelace","email":"ada@example.com","score":87,
                                    "eventDate":"2020-01-15","password":"Sup3r$ecret!",
                                    "confirmStart":"2026-11-01","confirmEnd":"2026-11-08"}'

                                # The strict twin agrees (same verdict, 200):
                                curl -s -X POST localhost:8080/api/demo/validation/arena/strict \\
                                  -H 'Content-Type: application/json' -d '{
                                    "name":"Ada Lovelace","email":"ada@example.com","score":87,
                                    "eventDate":"2020-01-15","password":"Sup3r$ecret!",
                                    "confirmStart":"2026-11-01","confirmEnd":"2026-11-08"}'"""),
                        code("term:violations", "bash", "every violation class, one kitchen-sink payload — the analyst explains, the strict endpoint envelopes", null, """
                                # Every class at once: blank name, malformed email, out-of-range score,
                                # future eventDate, blank password, inverted confirm window:
                                curl -s -X POST localhost:8080/api/demo/validation/arena \\
                                  -H 'Content-Type: application/json' -d '{
                                    "name":"","email":"not-an-email","score":101,
                                    "eventDate":"2030-01-01","password":"",
                                    "confirmStart":"2026-11-08","confirmEnd":"2026-11-01"}'
                                # → 200 { "accepted": false, "perField": [ … NotBlank … Email … Max …
                                #        Past … StrongPassword … a @DateRange class-level row ],
                                #        "envelopeComparison": { "error": { fields, code, message, requestId } } }

                                # The SAME payload against the strict endpoint = the real envelope:
                                curl -i -X POST localhost:8080/api/demo/validation/arena/strict \\
                                  -H 'Content-Type: application/json' -d '{
                                    "name":"","email":"not-an-email","score":101,
                                    "eventDate":"2030-01-01","password":"",
                                    "confirmStart":"2026-11-08","confirmEnd":"2026-11-01"}'
                                # → HTTP/1.1 400 · x-request-id echoed · {"error":{"fields":{…},
                                #    "code":"validation_failed","message":"request body failed validation",…}}"""),
                        code("term:edges", "bash", "the edges: null-tolerant optionals, a half-present window, and bytes that never parse", null, """
                                # Optional fields are null-tolerant: @Past skips null, and an absent
                                # window is fine — this payload is ACCEPTED (only eventDate omitted):
                                curl -s -X POST localhost:8080/api/demo/validation/arena \\
                                  -H 'Content-Type: application/json' -d '{
                                    "name":"Grace","email":"grace@example.com","score":99,
                                    "password":"Sup3r$ecret!"}'

                                # One date without its partner — the class-level net catches the pair:
                                curl -s -X POST localhost:8080/api/demo/validation/arena \\
                                  -H 'Content-Type: application/json' -d '{
                                    "name":"Grace","email":"grace@example.com","score":99,
                                    "password":"Sup3r$ecret!","confirmStart":"2026-11-01"}'
                                # → perField gains a "confirm window (confirmStart, confirmEnd)" row: DateRange

                                # A body that never parses is a 400 envelope, not a 500 mystery:
                                curl -i -X POST localhost:8080/api/demo/validation/arena \\
                                  -H 'Content-Type: application/json' -d '{"name": '
                                # → HTTP/1.1 400 {"error":{"code":"validation_failed",
                                #    "message":"request body is not readable JSON","requestId":"…"}}""")));
    }
}

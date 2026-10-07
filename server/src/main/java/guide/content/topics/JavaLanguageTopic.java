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
 * Topic 01 — Java Language Fundamentals. The exemplar for every topic that
 * follows: concept-first prose, code quoted from this repo's real source
 * (SnippetSourcesTest verifies each quote mechanically), and the topic's
 * live demo mounted INLINE in the section that teaches it.
 */
public final class JavaLanguageTopic {

    private JavaLanguageTopic() {
    }

    public static Topic topic() {
        return new Topic("java-language", "Java Language Fundamentals",
                "The language layer every Spring Boot server is written in: types, null, "
                        + "concurrency, erasure — and what the compiler proves before a request ever arrives.",
                TopicGroupId.FUNDAMENTALS, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-java-on-the-server", "Why Java on the server",
                        "Static typing at compile time vs dynamic typing discovered mid-request — and the honest trade each makes.",
                        text("why:static", """
                                Concept first: Java is statically typed — every variable, parameter, and return value has a type the compiler proves before the program ever runs. On a server, that timing is everything. In a dynamically typed language those checks happen while a request is in flight: a mistyped property name or a call with arguments in the wrong order becomes a runtime error discovered by whoever is on call. In Java those same mistakes are compile errors at your desk. A whole family of failures does not ship.

                                That is the trade in one sentence: you spend ceremony up front describing your data, and in exchange an entire class of bugs becomes impossible to deploy. The descriptions keep paying after the code is written: an IDE renames a method across every call site, a framework reads your types to wire components, and a newcomer learns what a function accepts without reading its body."""),
                        text("why:ecosystem", """
                                The language is only half the reason. The JVM underneath has been refined for three decades: a just-in-time compiler that optimizes exactly the hot paths your server actually runs, garbage collectors that pause for microseconds, and a bytecode format that made Java's library ecosystem the deepest in enterprise computing. Mature connectors exist for every database, message broker, and cloud vendor — usually several, maintained for a decade or more.

                                On top of that sits Spring, the framework this guide teaches, running a large share of the world's enterprise backends. That dominance compounds: hiring pools, documentation, and a generation of production incidents already fixed in the defaults you inherit. You are not choosing a language; you are choosing thirty years of other people's outages, already repaired."""),
                        text("why:tradeoffs", """
                                Honest accounting, though: Java charges for all this. It is verbose — or was; records, var, and pattern matching have closed much of the gap, and this guide leans on them hard. Startup time is real: a JVM must load classes and warm up before serving at full speed — it matters for CLIs and scale-to-zero, little for a server that runs for months. And there is ceremony: exceptions to declare, types to name, structure to maintain.

                                What you buy is type safety that survives refactoring, tooling that reasons about programs better than any dynamic peer, steady top-tier performance without heroics, and support timelines measured in decades. For a long-lived backend the ledger favors Java; for a one-off script it does not. Knowing which side of that line your work is on is the actual skill.""")),
                section("types-you-will-use", "Types you will actually use",
                        "Primitives and their boxed traps, immutable Strings, records, enums with behavior, sealed hierarchies — the daily toolkit, straight from this repo's own types.",
                        text("types:primitives", """
                                Concept first: Java splits its types in two. Primitives — int, long, boolean, double — are raw values: eight bytes or fewer, never null, copied on assignment. Everything else is a reference: a pointer to an object on the heap, compared with == only when you mean identity. Many non-null bugs come from forgetting which half you are standing in.

                                The wrapper types — Integer, Long, Boolean — object-box primitives, and the seams leak. == on two Integers compares references, not values; the JVM caches small values (−128 to 127), so == accidentally works for small numbers and then fails in production on large ones. Put a null Integer into arithmetic and the automatic unboxing throws a NullPointerException from a line containing no visible call. The rules this guide follows: primitives by default; wrappers only when null or generics force them (collections cannot hold primitives); never compare wrappers with ==.

                                Strings are the other daily type. They are immutable — every "modification" returns a new String — which is why they are safe to share across threads and to use as map keys. Concatenation in a loop was the classic performance trap; modern javac compiles + into efficient machinery, but String.join and formatted() still state intent more clearly."""),
                        text("types:records", """
                                Before Java 14, a data carrier was a class with a field, a constructor, a getter, equals, hashCode, and toString — six pieces of boilerplate per field, all hand-written, all easy to get subtly wrong. A record generates all of it from one line: new TextBlock(id, body) constructs, block.id() reads, two records with equal components are equal, and the printed form is readable. The API is positional — you trade named-argument clarity for brevity, which is why records shine for small obvious shapes and not for sprawling optional-laden ones.

                                The compact constructor is the one hook records give you for validation and normalization: it runs inside the canonical constructor, sees the raw arguments, and can reject or repair them before the record exists. This repo's whole content model is records, and its Failure value guards itself exactly this way — a Failure cannot exist without a kind and a message, and its details map is defensively copied against later mutation."""),
                        code("types:record-text-block", "java",
                                "guide/content/TextBlock.java — a one-line data carrier: constructor, accessors, equals, hashCode, toString, all generated",
                                "server/src/main/java/guide/content/TextBlock.java", """
                                public record TextBlock(String id, String body) implements ContentBlock {

                                    @JsonProperty("kind")
                                    @Override
                                    public String kind() {
                                        return "text";
                                    }
                                }"""),
                        code("types:record-failure", "java",
                                "guide/core/errors/Failure.java — the compact constructor validates and defensively copies",
                                "server/src/main/java/guide/core/errors/Failure.java", """
                                public record Failure(FailureKind kind, String message, Map<String, Object> details) {

                                    public Failure {
                                        java.util.Objects.requireNonNull(kind, "kind");
                                        java.util.Objects.requireNonNull(message, "message");
                                        details = details == null ? null : Map.copyOf(details);
                                    }"""),
                        text("types:enums", """
                                An enum is not a named integer — it is a fixed set of instances of a real class, each carrying fields and behavior. That makes it the type-safe answer to "closed set of options": the compiler knows every value that can exist, switch over an enum can be checked for exhaustiveness, and the extra state travels WITH the option instead of in a parallel lookup table.

                                This repo's error taxonomy is the showcase. FailureKind pairs each failure kind with its HTTP status and its machine-readable error code as enum fields — so the mapping from "not found" to 404 to "not_found" lives in exactly one place and can never drift between layers. One enum constant per kind, one constructor storing two fields, two accessors."""),
                        code("types:enum", "java",
                                "guide/core/errors/FailureKind.java — an enum with behavior: each constant carries its HTTP status and error code",
                                "server/src/main/java/guide/core/errors/FailureKind.java", """
                                public enum FailureKind {
                                    VALIDATION(400, "validation_failed"),
                                    UNAUTHORIZED(401, "unauthorized"),
                                    // … six more kinds, one constant each …
                                    INTERNAL(500, "internal_error");

                                    private final int httpStatus;
                                    private final String errorCode;

                                    FailureKind(int httpStatus, String errorCode) {
                                        this.httpStatus = httpStatus;
                                        this.errorCode = errorCode;
                                    }

                                    public int httpStatus() {
                                        return httpStatus;
                                    }

                                    public String errorCode() {
                                        return errorCode;
                                    }
                                }"""),
                        text("types:sealed", """
                                Sealed interfaces close the other axis: not "which values" but "which implementations". A sealed interface lists exactly the classes permitted to implement it; anything else fails to compile. Combine that with pattern matching for switch and the payoff lands: a switch over a sealed type is exhaustive with no default arm, and adding a new implementation turns every incomplete switch into a compile error instead of a silently missed case.

                                The most-used type in this repo's domain layer is exactly that: Result<T> is a sealed interface with two permitted implementations, Success and Failed. The map helper below pattern-matches both arms in one expression — and because the permit list is closed, the switch needs no default and can never meet an implementation it does not know about."""),
                        code("types:sealed-result", "java",
                                "guide/core/result/Result.java — a sealed interface plus an exhaustive pattern-matching switch over its two arms",
                                "server/src/main/java/guide/core/result/Result.java", """
                                public sealed interface Result<T> permits Success, Failed {

                                    static <T> Result<T> success(T value) {
                                        return new Success<>(value, java.util.List.of());
                                    }

                                    // … failed(), isSuccess() elided …

                                    default <U> Result<U> map(java.util.function.Function<? super T, ? extends U> mapper) {
                                        return switch (this) {
                                            case Success<T> success -> new Success<>(mapper.apply(success.value()), success.warnings());
                                            case Failed<T> failed -> new Failed<>(failed.failure());
                                        };
                                    }
                                }""")),
                section("null-optional-defensive", "null, Optional, and defensive coding",
                        "Tony Hoare called null his billion-dollar mistake. Java cannot remove it — Optional, requireNonNull, and a few conventions keep it at the boundaries.",
                        text("null:mistake", """
                                Tony Hoare, who introduced the null reference in 1965, later called it his "billion-dollar mistake": a value that is silently a member of every reference type, with no annotation and no type. The problem is not null itself but its reach — it can travel through any reference anywhere, so the place you discover it (a NullPointerException three frames from the cause) is rarely the place it was born.

                                Java cannot remove null, but a codebase can contain it. This guide's strategy is three moves. Never return null where absence is meaningful — return Optional, making "possibly empty" part of the signature so every caller must acknowledge it. Never store null where you can validate it away — records with compact constructors, and Objects.requireNonNull at construction boundaries. And treat genuine boundary nullability explicitly: validate input at the edge, or annotate with your project's @Nullable convention. One boundary for Optional: a return-type vocabulary, never a field or parameter type — it is not serialization-friendly, and a null Optional is a paradox the type cannot express."""),
                        code("null:registry", "java",
                                "guide/content/ContentRegistry.java — a lookup whose absence is expressed, not returned as null",
                                "server/src/main/java/guide/content/ContentRegistry.java", """
                                /** Exact-match lookup; empty when no topic carries that id. */
                                public Optional<Topic> getTopicById(String id) {
                                    if (id == null) {
                                        return Optional.empty();
                                    }
                                    return topics.stream().filter(topic -> topic.id().equals(id)).findFirst();
                                }"""),
                        text("null:usecase", """
                                The caller then converts absence into the app's real error vocabulary. The GetTopicById use case maps the Optional into a Result: present becomes Success carrying the topic detail; absent becomes a Failed carrying a typed not-found Failure — with the offending topic id preserved in the failure's details, so the error envelope can echo it. That chain — Optional.map to transform the present case, orElseGet to manufacture the failure — is the idiom at every repository-to-use-case seam in this guide. Notice also the first guard: a blank id never reaches the repository at all; it is rejected as a validation failure up front."""),
                        code("null:usecase-code", "java",
                                "guide/domain/content/GetTopicById.java — Optional mapped into a typed Result failure",
                                "server/src/main/java/guide/domain/content/GetTopicById.java", """
                                public Result<TopicDetail> execute(String id) {
                                    if (id == null || id.trim().isEmpty()) {
                                        return Result.failed(Failure.of(FailureKind.VALIDATION,
                                                "topic id must be a non-empty string", Map.of("parameter", "id")));
                                    }
                                    return repository.findTopicById(id)
                                            .map(topic -> Result.<TopicDetail>success(TopicDetail.of(topic)))
                                            .orElseGet(() -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                                                    "no topic with id \\"" + id + "\\"", Map.of("topicId", id))));
                                }"""),
                        text("null:boundaries", """
                                The remaining move is failing fast at construction. Objects.requireNonNull(x, "x") throws immediately — with a useful message — at the boundary where the bad value entered, instead of three layers later. The Failed record demands exactly that: a failure arm that itself lacks a failure is nonsense, so the compact constructor refuses to build one.

                                A note on @Nullable: Java has no built-in nullability syntax, so annotations (jakarta, JetBrains, Spring's own) are tooling-enforced conventions. This repo's convention is simpler and stronger — final fields, records that validate in compact constructors, Optional returns — so that by the time an object exists, null already had its one chance to be rejected."""),
                        code("null:failed", "java",
                                "guide/core/result/Failed.java — requireNonNull at the construction boundary",
                                "server/src/main/java/guide/core/result/Failed.java", """
                                public record Failed<T>(Failure failure) implements Result<T> {

                                    public Failed {
                                        java.util.Objects.requireNonNull(failure, "failure");
                                    }
                                }""")),
                section("concurrency-toolkit", "Concurrency: threads, executors, CompletableFuture",
                        "A server serves many requests at once on many threads. The toolkit is small: locks or immutability, executors, composable futures — and virtual threads waiting in topic 02.",
                        text("conc:threads", """
                                A JVM server serves many requests at once, on many threads: each request gets a thread, each thread has its own call stack, and they all see the same heap. That last clause is both the feature and the trap — sharing state across threads is where the worst bugs live: timing-dependent, absent from tests, present at 3 a.m. under load.

                                You have exactly two disciplined answers. Lock the shared state: synchronized admits one thread at a time into a critical section. Or stop sharing: immutable state needs no lock because nobody can write it — a record that returns new instances instead of mutating is thread-safe by construction. This guide's ScenarioStore is the honest first example — real mutable state, because demo endpoints PATCH it at runtime — so it locks: apply() is synchronized (the read-modify-write must be atomic), the state field is volatile (every thread sees the freshest reference), and the per-area overrides live in a ConcurrentHashMap, thread-safe by structure."""),
                        code("conc:store", "java",
                                "guide/core/scenario/ScenarioStore.java — the one piece of shared mutable state in this app, guarded three ways",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                public final class ScenarioStore {

                                    private final Map<String, ScenarioDataMode> areaModes = new ConcurrentHashMap<>();
                                    private volatile ScenarioState state;
                                    // … constructor and getters elided …

                                    /** Read-modify-write on shared state — the lock is what makes it atomic. */
                                    public synchronized ScenarioState apply(ScenarioPatch patch) {
                                        Objects.requireNonNull(patch, "patch");
                                        ScenarioState current = state;
                                        ScenarioState next = new ScenarioState(
                                                patch.dataMode() != null ? patch.dataMode() : current.dataMode(),
                                                patch.injectFailure() != null ? patch.injectFailure() : current.injectFailure());
                                        state = next;
                                        return next;
                                    }
                                }"""),
                        text("conc:executors", """
                                Bare threads are for demonstrations; a server never manages them by hand. The ExecutorService is the abstraction that grows up: you submit tasks, the pool owns the threads and hands them work, and you get Futures back — handles to results that may not exist yet. future.get() blocks until the result is ready, which is exactly right at a request boundary and exactly wrong inside a task the pool should be running.

                                The lifecycle rule people forget: a pool left un-shutdown keeps its threads alive — and keeps the JVM alive with them. Shutdown belongs in finally, always. The quoted exhibit is honest about its own status: this guide's server has not needed a pooled executor yet (the content API is in-memory and synchronous), so this code is a domain/teaching exhibit — compiled and unit-tested, awaiting a real pipeline to replace it."""),
                        code("conc:pool", "java",
                                "guide/domain/teaching/ExhibitConcurrency.java — submit tasks, collect Futures, shut down in finally",
                                "server/src/main/java/guide/domain/teaching/ExhibitConcurrency.java", """
                                public static long pooledSum(int tasks, int perTask) throws Exception {
                                    ExecutorService pool = Executors.newFixedThreadPool(4);
                                    try {
                                        List<Future<Long>> futures = new ArrayList<>();
                                        for (int index = 0; index < tasks; index++) {
                                            futures.add(pool.submit(() -> (long) perTask));
                                        }
                                        long total = 0;
                                        for (Future<Long> future : futures) {
                                            total += future.get();
                                        }
                                        return total;
                                    } finally {
                                        pool.shutdown();
                                        // … awaitTermination + shutdownNow elided — never exit
                                        // without offering the pool a shutdown
                                    }
                                }"""),
                        text("conc:completable", """
                                CompletableFuture is the composable layer over futures: supplyAsync starts a task on a pool, thenCompose chains a second async stage that needs the first one's result, thenApply transforms a value, exceptionally recovers a failed pipeline. The pipeline is a value: declare the dependency graph once, and independent stages overlap instead of queueing.

                                Two habits keep it honest on a server. Join at the edge: join() converts the pipeline back into a synchronous call — fine at the controller boundary, a hazard mid-pipeline where it reintroduces blocking. And know your pool: with no executor argument every stage runs on the shared common ForkJoinPool, sized to your CPU cores — right for CPU work, wrong for blocking I/O, where you hand each stage the executor it should run on (topic 02's virtual threads change this arithmetic completely)."""),
                        code("conc:pipeline", "java",
                                "guide/domain/teaching/ExhibitConcurrency.java — supplyAsync, thenCompose, exceptionally: a pipeline with recovery",
                                "server/src/main/java/guide/domain/teaching/ExhibitConcurrency.java", """
                                CompletableFuture<Long> first = CompletableFuture.supplyAsync(
                                        () -> failFirstStage ? throwFailure() : (long) input * input);
                                CompletableFuture<Long> result = first
                                        .thenCompose(square -> CompletableFuture.supplyAsync(() -> square + 1))
                                        .exceptionally(error -> {
                                            // recovery: the pipeline still completes — with a fallback
                                            return -1L;
                                        });
                                return result.join();"""),
                        text("conc:virtual", """
                                Since JDK 21 there is a third actor: virtual threads — cheap threads the JVM parks while they block on I/O and resumes afterwards, letting you write straightforward blocking code that scales like async. Creating the executor is one line, and because it is AutoCloseable, try-with-resources manages its lifecycle (that snippet appears in the exceptions section below, teaching both lessons at once). Topic 02 dissects how virtual threads actually work; for now just know the executor you saw above has a modern sibling.""")),
                section("packages-jars-classpath", "Packages, jars, and the classpath",
                        "How Java finds code: packages into directories, classes into jars, jars onto a classpath — and how Maven turns all of it into conventions you stop thinking about.",
                        text("pkg:packages", """
                                A package is two things at once: a namespace (guide.content vs guide.core.result) and a directory layout (guide/content vs guide/core/result). The package declaration at the top of a file must match its directory — the compiler enforces it — and the fully-qualified name guide.content.ContentRegistry is globally unique. Imports are local aliases over that scheme: an import names exactly one class (or static member), and that is all; there is no module resolution algorithm to learn.

                                This repo chose a single-segment root package, guide. Convention says reverse-domain (com.company.project) and libraries should keep following it — but a top-level single segment is perfectly legal Java, and for a teaching codebase guide.content.ContentRegistry reads dramatically better than com.example.springbootguide.content.ContentRegistry in every snippet across the forty-three topics that follow. The entire application in one glance:"""),
                        code("pkg:app", "java",
                                "server/src/main/java/guide/GuideApplication.java — the root of the package tree, verbatim",
                                "server/src/main/java/guide/GuideApplication.java", """
                                package guide;

                                import org.springframework.boot.SpringApplication;
                                import org.springframework.boot.autoconfigure.SpringBootApplication;

                                @SpringBootApplication
                                public class GuideApplication {

                                    public static void main(String[] args) {
                                        SpringApplication.run(GuideApplication.class, args);
                                    }
                                }"""),
                        text("pkg:jars", """
                                javac compiles each .java into a .class file of JVM bytecode. A jar is a zip of class files plus a manifest; the classpath is the ordered list of places the JVM looks when a class is first referenced — directories of .class files, jars, or both — and first match wins. That "first match wins" is the classic classpath-hell mechanism: two versions of one class on a classpath and you get whichever was listed first, silently.

                                Java 9 added the module path (JPMS): jars with explicit dependencies and strong encapsulation. It is real and useful for the JDK's own internals, but the Spring ecosystem ships on the classpath, and that is what this guide uses. What Spring Boot adds on top is the executable boot jar: your classes plus every dependency embedded under BOOT-INF/lib, launched by Boot's own launcher — java -jar server.jar and nothing else on the machine."""),
                        code("pkg:pom", "xml",
                                "server/pom.xml — coordinates name the artifact; the parent BOM pins every Spring version (dependencies elided)",
                                "server/pom.xml", """
                                <parent>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-parent</artifactId>
                                    <version>4.1.1</version>
                                    <relativePath/>
                                </parent>

                                <groupId>guide</groupId>
                                <artifactId>server</artifactId>
                                <version>0.0.1-SNAPSHOT</version>"""),
                        text("pkg:maven", """
                                Maven turns the rest into conventions you stop thinking about. Source lives in src/main/java, tests in src/test/java; compilation lands in target/classes; the spring-boot-maven-plugin assembles the boot jar in target. Coordinates — groupId, artifactId, version — name every artifact uniquely, and because the parent POM manages versions, your own dependency list stays one line per starter, with no version numbers to drift out of date.

                                The application.yml beside the code is the runtime counterpart to the build: structure the artifact with Maven, structure the running process with configuration. Both are quoted here exactly as this server ships them."""),
                        code("pkg:yml", "yaml",
                                "server/src/main/resources/application.yml — the running process's shape, verbatim",
                                "server/src/main/resources/application.yml", """
                                spring:
                                  application:
                                    name: springboot-developers-guide

                                server:
                                  port: 8080""")),
                section("generics-erasure-reflection", "Generics are erased; reflection sees",
                        "Generics are a compile-time proof that is deleted before the code runs — but the declarations, and everything reflection reads from them, survive in the class file.",
                        text("era:mechanics", """
                                Concept first: generics are a compile-time proof that is deleted before the code runs. List<String> and List<Integer> are the same class at runtime — one Class object, java.util.ArrayList, with no memory of what you parameterized it with. This is type erasure: the price Java paid in 2004 so that generic code could run unchanged against a decade of non-generic libraries.

                                The consequences reach you weekly: no new T[] or instanceof List<String>; two methods differing only in a generic parameter are the same method after erasure, so they collide; and a cast the compiler inserted for you can fail far from where the bad element entered — the infamous heap-pollution ClassCastException, thrown in code with no visible cast.

                                But erasure is selective — the fact most developers miss: what is erased is the parameterization of instances — what a particular List is a list OF. The declarations survive in the class file: a field declared List<String> carries that signature into the bytecode, and reflection reads it back. So getGenericType() on a declared field tells you List<String>, while getClass() on the instance can only ever tell you ArrayList. Declaration kept, instance forgotten — hold that distinction; the demo below exists to make it visible."""),
                        text("era:reflection", """
                                Reflection is the JVM's self-inspection API: Class, Method, Field, annotations — the metadata a running program can read about itself. It sees a lot: record component names AND their declared types (records store them properly — the reason records deserialize so cleanly), sealed permit lists (a sealed interface knows its implementations at runtime), annotations (a @JsonProperty shapes the JSON this very API serves), method signatures with their generic declarations.

                                This is not an exotic corner — it is the load-bearing trick of the whole ecosystem. Spring reads annotations and constructors to instantiate and inject beans. Jackson turns records into JSON by walking their components. Hibernate reads entity metadata to generate SQL. Every "magic" framework feature is reflection plus conventions. The code below is the actual exhibit the demo endpoint runs: the loop reads declared generic signatures from Catalog's fields, and the identity comparison asks the runtime the one question erasure answers — whether a Box<String> and a Box<Integer> are different classes. They are not."""),
                        code("era:exhibit", "java",
                                "guide/domain/java/RuntimeTypesExhibit.java — the real exhibit behind the demo: declared generics readable, instance parameterization gone",
                                "server/src/main/java/guide/domain/java/RuntimeTypesExhibit.java", """
                                for (Field field : Catalog.class.getDeclaredFields()) {
                                    fields.add(new FieldView(field.getName(), field.getGenericType().getTypeName(),
                                            field.getType().getTypeName()));
                                }
                                // …
                                Box<String> strings = new Box<>(List.of("guide", "boot", "jvm"));
                                Box<Integer> numbers = new Box<>(List.of(1, 2, 3));
                                // …
                                boolean erased = strings.getClass() == numbers.getClass();"""),
                        demo("era:demo", "java:runtime-types", "Runtime types, live",
                                "Point the reflection inspector at this repo's own classes (Result, TextBlock, Topic) and watch it report record components, sealed permits, and annotations — then see erasure side by side: declared generic field types survive, while two differently-parameterized Box instances share one raw Class.")),
                section("exceptions-try-with-resources", "Exceptions and try-with-resources",
                        "Checked vs unchecked, failures as values, causes that stay chained, resources the language closes for you — and nothing ever swallowed.",
                        text("exc:philosophy", """
                                Java's original experiment was the checked exception: some methods fail in ways callers MUST handle (any I/O can throw IOException), so the compiler forces a catch or a throws clause. Three decades in, the verdict is mixed: checked exceptions compose terribly with lambdas and streams (Function has no throws clause), they push tired developers toward empty catch blocks, and they embed a failure policy into every method signature.

                                Modern API design — and this guide — lands on the other side: expected failures are values, not throws. Use cases return Result<T> carrying a typed Failure; unchecked exceptions are reserved for the edge that converts a Failed Result into what Spring understands. The AppException hierarchy below is that edge: each subclass maps one FailureKind, and the of() factory pattern-switches a Failure into the matching exception class. Note the second constructor: exceptions must chain — rethrow with the original as the cause, or you delete the actual failure's stack trace, the debugging equivalent of burning the evidence.

                                And one absolute: never swallow. An empty catch block converts a visible failure into silent corruption. Every failure in this app leaves through one pipeline, logged with a request id — there is no path where an exception simply vanishes."""),
                        code("exc:app", "java",
                                "guide/core/errors/AppException.java — the unchecked edge: typed exceptions, cause chaining, one factory",
                                "server/src/main/java/guide/core/errors/AppException.java", """
                                public class AppException extends RuntimeException {

                                    private final Failure failure;

                                    public AppException(Failure failure) {
                                        super(failure.message());
                                        this.failure = failure;
                                    }

                                    public AppException(Failure failure, Throwable cause) {
                                        super(failure.message(), cause);
                                        this.failure = failure;
                                    }

                                    // … accessors elided …

                                    public static AppException of(Failure failure) {
                                        return switch (failure.kind()) {
                                            case VALIDATION -> new ValidationException(failure);
                                            case NOT_FOUND -> new NotFoundException(failure);
                                            // … one arm per FailureKind …
                                            case INTERNAL -> new InternalException(failure);
                                        };
                                    }
                                }"""),
                        text("exc:resources", """
                                Some failures are simpler than policy: you just must not leak. Sockets, files, streams, connections — all need closing whether the block succeeds or throws. Java 7's answer is try-with-resources: declare the resource in the try header and the compiler writes the finally for you — close() runs in reverse declaration order on every path, and a suppressed exception rides along instead of clobbering the primary one. Anything AutoCloseable works, and since JDK 19 that includes ExecutorService — the virtual-thread snippet below is the canonical modern example: open the executor in the try header, submit tasks, and close() waits for them.

                                The older shape, try/finally, survives where the cleanup is not a close() method. RequestIdFilter is the canonical case: it stamps a correlation id into the logging MDC before the request runs and must clear it afterwards. MDC is thread-local state, not a resource, so it gets no AutoCloseable — but the requirement is identical: the cleanup must happen on every path, or one request's id would label the next request's log lines. Same discipline, different syntax."""),
                        code("exc:try", "java",
                                "guide/domain/teaching/ExhibitConcurrency.java — try-with-resources over an ExecutorService (AutoCloseable since JDK 19)",
                                "server/src/main/java/guide/domain/teaching/ExhibitConcurrency.java", """
                                try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
                                    List<Future<Integer>> futures = new ArrayList<>();
                                    for (int index = 0; index < tasks; index++) {
                                        futures.add(virtual.submit(() -> 1));
                                    }
                                    long total = 0;
                                    for (Future<Integer> future : futures) {
                                        total += future.get();
                                    }
                                    return total;
                                }"""),
                        code("exc:filter", "java",
                                "guide/core/logging/RequestIdFilter.java — try/finally where the cleanup is not a resource",
                                "server/src/main/java/guide/core/logging/RequestIdFilter.java", """
                                MDC.put(MDC_KEY, requestId);
                                try {
                                    filterChain.doFilter(request, response);
                                } finally {
                                    MDC.remove(MDC_KEY);
                                }""")),
                section("streams-lambdas", "Streams, lambdas, and functional interfaces",
                        "Lambdas implement functional interfaces; streams turn collections into pipelines. Powerful for mapping and filtering — and worth avoiding in hot loops.",
                        text("str:concept", """
                                A lambda is an inline implementation of a functional interface — any interface with exactly one abstract method. The JDK ships a small vocabulary of them: Function<T,R> (transform), Predicate<T> (test), Supplier<T> (produce), Consumer<T> (consume). A method reference like TopicSummary::from is the same lambda when the body is just "call this one method" — read it as a function value, not as code that belongs to a class.

                                Streams compose those functions over collections: source.stream(), then lazy intermediate operations (filter, map, sorted) that do nothing until a terminal operation (toList, findFirst, forEach, reduce) pulls values through the pipeline. The laziness matters: no stage computes anything the terminal operation does not need — findFirst genuinely stops at the first match. You have already met two of this repo's streams: the registry's lookup (filter + findFirst, in the null section) and the summary list below, where map with a method reference converts each Topic into its wire DTO — records plus streams is the entire DTO-mapping idiom."""),
                        code("str:summaries", "java",
                                "guide/content/ContentRegistry.java — map with a method reference: Topic in, DTO out, in one line",
                                "server/src/main/java/guide/content/ContentRegistry.java", """
                                public List<TopicSummary> getTopicSummaries() {
                                    return topics.stream().map(TopicSummary::from).toList();
                                }"""),
                        code("str:dto", "java",
                                "guide/domain/content/TopicList.java — a stream over an enum builds the nav groups",
                                "server/src/main/java/guide/domain/content/TopicList.java", """
                                public static TopicList of(List<TopicSummary> topics) {
                                    List<TopicGroup> groups = Arrays.stream(TopicGroupId.values())
                                            .map(group -> new TopicGroup(group.wireName(), group.title(), group.order()))
                                            .toList();
                                    return new TopicList(ContentModel.CONTENT_API_VERSION, groups, topics);
                                }"""),
                        text("str:when", """
                                Streams are a readability tool with real costs, and knowing where NOT to use them is the skill. Debugging: a stack trace through a five-stage pipeline drops you into lambda frames with no line numbers you own. Hot loops: each stage allocates lambda instances and stream machinery — irrelevant at forty items, measurable in a hot loop over millions. Complex state: any pipeline needing indexes, early exits with side effects, or two collections in lockstep fights the API — the plain for-loop is not a style failure, and this codebase uses loops where the logic is stateful (the registry's own validation walks topics with explicit sets, no stream in sight).

                                The ratchet test that enforces this guide's snippet sourcing is the sweet spot: one filter, one map, one toList — linear, allocation-insensitive, clearer than the loop equivalent. That is the decision procedure: mapping or filtering a collection into another shape, streams; anything with state, indexes, or heat, a loop."""),
                        code("str:ratchet", "java",
                                "server/src/test/java/guide/content/SnippetSourcesTest.java — the suite's own filter/map chain, enforcing snippet sourcing",
                                "server/src/test/java/guide/content/SnippetSourcesTest.java", """
                                List<String> unsourced = mustSourceBlocks().stream()
                                        .filter(ref -> ref.block().source() == null)
                                        .map(ref -> ref.topicId() + " | " + ref.block().id() + " | " + ref.block().language())
                                        .toList();""")),
                section("terminal-companion", "The two endpoints from a terminal",
                        "Every topic ends at a terminal: the endpoints you just read about, driven by curl.",
                        text("term:tour", """
                                curl is the honest client — no build step, no framework, just what the wire carries. You have now met every piece of this topic on the wire: the content index is the registry's summaries; the topic detail is the section tree this page rendered; the demo endpoints run the reflection exhibit and the erasure evidence you operated above; health proves the process is up.

                                Watch the shapes as much as the data: the version field on every content payload, the Optional-shaped absence of an unknown topic id becoming a typed not-found envelope (complete with requestId), and the reflection endpoint's validation failure for a class outside the whitelist. Start the server (docker compose up, or run GuideApplication), then take the tour:"""),
                        code("term:curl", "bash", "the curl tour of topic 01", null, """
                                # The whole curriculum — groups plus topic summaries:
                                curl -s localhost:8080/api/content | head -40

                                # This topic, the full section tree:
                                curl -s localhost:8080/api/content/java-language | head -80

                                # The demo from the erasure section — reflection on Result (sealed!):
                                curl -s 'localhost:8080/api/demo/java/reflection?class=result'

                                # …on TextBlock — a record's components, visible at runtime:
                                curl -s 'localhost:8080/api/demo/java/reflection?class=text-block'

                                # Erasure side by side — declared generics kept, instances erased:
                                curl -s localhost:8080/api/demo/java/erasure

                                # And an unknown class names the whitelist, in the 400 envelope:
                                curl -i 'localhost:8080/api/demo/java/reflection?class=string-builder'

                                # The one endpoint every service owes you:
                                curl -s localhost:8080/api/health""")));
    }
}

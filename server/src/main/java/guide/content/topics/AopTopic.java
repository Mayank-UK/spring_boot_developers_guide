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
 * Topic 06 — Aspect-Oriented Programming. The topic that ADDS this app's
 * first real aspect: the InvocationTraceAspect in guide/core/aop (plus its
 * @Traceable marker, InvocationLog ring, the teaching exhibits for the
 * self-invocation trap, and three live demos under /api/demo/aop/). Every
 * snippet is quoted from that real code — SnippetSourcesTest verifies each
 * quote mechanically; the demos report what the RUNNING machinery actually
 * did, including the trap.
 */
public final class AopTopic {

    private AopTopic() {
    }

    public static Topic topic() {
        return new Topic("aop", "Aspect-Oriented Programming",
                "One aspect, real: how Spring intercepts method calls through proxies, "
                        + "what @Transactional has quietly been doing all along, and the traps "
                        + "every proxy-based feature shares.",
                TopicGroupId.FUNDAMENTALS, 6,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-cross-cutting-problem", "The cross-cutting problem",
                        "Some concerns cut across every use case. Writing them by hand means pasting the same ceremony everywhere — this repo's real use cases show where it would land.",
                        text("aop:problem:framing", """
                                Concept first: some requirements do not belong to any ONE feature. Logging what was called and how long it took, retrying flaky calls, opening and committing transactions, checking permissions — each of these wants to wrap EVERY business operation, not one of them. A concern like that is called cross-cutting: it slices vertically through code that is organized horizontally (by feature, by use case). This repo's use cases are honest, focused domain code — and that is exactly why they make the problem visible.

                                Suppose we wanted what operations teams actually want from a backend: for every use-case invocation, a record of the method, a capped summary of its arguments, whether it succeeded or threw, and how long it took. Nothing in the domain needs that knowledge — and yet, written by hand, it would have to live INSIDE every use case. Look at where the ceremony would land in a real one, GetTopicById — the use case serving the very page you are reading:"""),
                        code("aop:problem:uctx", "java",
                                "guide/domain/content/GetTopicById.java — a real use case; the markers show where hand-written ceremony would land",
                                "server/src/main/java/guide/domain/content/GetTopicById.java", """
                                public class GetTopicById {

                                    private final ContentRepository repository;

                                    public GetTopicById(ContentRepository repository) {
                                        this.repository = repository;
                                    }

                                    public Result<TopicDetail> execute(String id) {   // ← ceremony: log method + args, start the clock
                                        if (id == null || id.trim().isEmpty()) {
                                            return Result.failed(Failure.of(FailureKind.VALIDATION,
                                                    "topic id must be a non-empty string", Map.of("parameter", "id")));
                                        }
                                                return repository.findTopicById(id)   // ← ceremony: try/finally so the exit is recorded even on throw
                                                .map(topic -> Result.<TopicDetail>success(TopicDetail.of(topic)))
                                                .orElseGet(() -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                                                        \"no topic with id \\\"\" + id + \"\\\"\", Map.of(\"topicId\", id))));
                                    }
                                }   // ← ceremony: log outcome + duration — four to six lines, every use case, forever"""),
                        text("aop:problem:math", """
                                Count the cost of pasting that ceremony in by hand. Every use case acquires the same five duties: read the clock on entry, render a SAFE summary of the arguments (never the full payload), catch-and-classify-and-RETHROW any exception, record the outcome in a finally block, and stamp the request id so the line correlates with the request that caused it. Multiply by every use case in a real system — dozens to hundreds — and by every new concern: timing, retries, metrics, audit. N operations times M concerns is N×M edit sites, each one a chance to paste it slightly wrong, each one noise a reader must step over to find the three lines of actual policy.

                                And the second real use case is the same shape — InspectHttpRequest, topic 03's header inspector. Different domain entirely; identical landing spots:"""),
                        code("aop:problem:http", "java",
                                "guide/domain/http/InspectHttpRequest.java — a second real use case, the same ceremony targets",
                                "server/src/main/java/guide/domain/http/InspectHttpRequest.java", """
                                public class InspectHttpRequest {

                                    private final HeadersExhibit exhibit;

                                    public InspectHttpRequest(HeadersExhibit exhibit) {
                                        this.exhibit = exhibit;
                                    }

                                    public Result<HeadersExhibit.InspectReport> execute(String method, String path, String rawQuery,
                                            List<HeadersExhibit.IncomingHeader> headers, HeadersExhibit.IncomingBody body) {   // ← same ceremony, same five duties
                                        if (method == null || method.isBlank() || path == null || path.isBlank()) {
                                            return Result.failed(Failure.of(FailureKind.VALIDATION,
                                                    "method and path must be non-empty strings",
                                                    Map.of("parameter", "method/path")));
                                        }
                                        return Result.success(exhibit.inspect(
                                                method.trim().toUpperCase(Locale.ROOT), path.trim(), rawQuery, headers, body));   // ← same finally-block exit
                                    }
                                }"""),
                        text("aop:problem:interception", """
                                There is an older observation hiding in that math: if every CALL into a use case must be surrounded by the same wrapper, stop editing the inside of every callee — interpose something at the boundary instead. Wrap the OBJECT once, and every caller's invocation passes through the wrapper before reaching the target. That is the decorator pattern, hand-written: a class implementing the same interface, holding the real instance, doing its ceremony around each delegation.

                                Aspect-oriented programming is that idea, industrialized. You declare WHAT to intercept (a pointcut) and WHAT to do there (advice); a factory builds the wrappers automatically. Spring's implementation of "wrap the object" is the proxy — and that one mechanical fact explains, at once, how @Aspect works, how @Transactional has been working all along, and every trap they share. Proxies first.""")),
                section("proxies-jdk-vs-cglib", "Proxies: JDK vs CGLIB",
                        "Spring cannot rewrite your methods, but it can hand callers a stand-in object — two runtime mechanisms build them, and Boot has already picked one for you.",
                        text("aop:proxy:mechanics", """
                                Start with the hard constraint that shapes everything else: the JVM will not let a framework rewrite the body of your compiled method at runtime (instrumentation agents aside). Interception therefore happens one level up — the container hands callers a STAND-IN object that has your bean tucked inside it. Callers think they hold the use case; they hold the proxy. Each call travels: caller → proxy (advice runs here) → your real object. Two runtime mechanisms can build that stand-in.

                                JDK dynamic proxies are the platform's own tool (java.lang.reflect.Proxy): given interfaces, it generates a class AT RUNTIME that implements them, forwarding every call to an InvocationHandler you supply. The constraint is structural: the proxy is generated FROM interfaces, so it is typed exactly as the interface — call sites that expect the concrete class do not accept it, and methods not on any interface simply do not exist on the proxy.

                                CGLIB (Code Generation Library, repackaged inside Spring) generates instead a SUBCLASS of your actual class at runtime, overriding each non-final method to route through the advice chain. No interface needed; the proxy IS-A your concrete type, so injection points do not change at all. The constraints come from subclassing: final classes cannot be subclassed, final methods are inherited but not overridden — silently un-advised — and private methods are never visible to a subclass call. Constructors run their usual field initialization (Spring instantiates the proxy with Objenesis precisely so no constructor side effects fire twice)."""),
                        text("aop:proxy:choice", """
                                Which one does Spring pick? In classic Spring Framework mode: if the bean implements an interface AND proxy-target-class is false — JDK proxy; otherwise CGLIB. In a Spring Boot application you can effectively forget the first branch: Boot has set spring.aop.proxy-target-class=true as its default since 2.0, so CGLIB is used even when interfaces exist. Why choose the subclass proxy as a default? Because type expectations never surprise you — @Autowired by concrete class works everywhere, and a bean that happens to gain or lose an interface does not change which mechanism proxies it. The cost is living permanently inside CGLIB's subclassing constraints (the final-method and private-method rules above).

                                This app's proxy demo encodes exactly that decision table and runs it against four of our own wired beans at request time — the verdict comes from Spring's own AopUtils, reading the objects the container actually hands out:"""),
                        code("aop:proxy:adapter", "java",
                                "guide/data/aop/ProxyInspectionAdapter.java — the verdict logic: proxied? which mechanism? and the rule that decided it",
                                "server/src/main/java/guide/data/aop/ProxyInspectionAdapter.java", """
                                private ProxyFacts factsFor(NamedBean named) {
                                    Object bean = context.getBean(named.beanName());
                                    Class<?> runtimeClass = bean.getClass();
                                    boolean proxied = AopUtils.isAopProxy(bean);
                                    Class<?> targetClass = proxied ? AopUtils.getTargetClass(bean) : runtimeClass;
                                    String userInterface = firstUserInterface(targetClass);
                                    String mechanism;
                                    String decisionRule;
                                    if (!proxied) {
                                        mechanism = "not proxied";
                                        decisionRule = "no advice's pointcut matches this bean — the container hands "
                                                + "out the plain instance, getClass() is the authored class";
                                    } else if (AopUtils.isCglibProxy(bean)) {
                                        mechanism = "CGLIB subclass proxy";
                                        decisionRule = userInterface == null
                                                ? "no interface on the target — the subclass proxy is the only mechanism"
                                                : "JDK-eligible (implements " + userInterface + ") but Boot's default "
                                                        + "spring.aop.proxy-target-class=true forces CGLIB";
                                    } else {
                                        mechanism = "JDK dynamic proxy";
                                        decisionRule = "interface-based proxy (proxy-target-class=false mode): implements "
                                                + "the target's interfaces, delegating to the real object";
                                    }
                                    // … assembles the ProxyFacts record: names, classes, verdict, interfaces …
                                }"""),
                        demo("aop:proxy:demo", "aop:proxy", "What did the container really hand out?",
                                "Ask the running app how it distributed its own beans: for a traced use case, an interface-bearing exhibit, an untraced use case, and the aspect itself — the actual runtime class (with its $$ proxy markers), the interfaces the object grew, a proxied-or-not verdict, and the rule that decided each one. This is not a diagram: the demo reads the live objects through Spring's AopUtils.")),
                section("aspect-anatomy", "Anatomy of an aspect",
                        "One dependency, one annotation, five advice kinds — and this app's real InvocationTraceAspect, dissected line by line.",
                        text("aop:anatomy:enablement", """
                                The machinery is one starter away — the single pom change this topic made to the app. Note the name: Boot 4 renamed the classic spring-boot-starter-aop to spring-boot-starter-aspectj (the old name is no longer in the BOM — asking for it fails the build with a missing-version error, which is how renames announce themselves). The starter brings spring-aop (the proxy factory and advice model) and the AspectJ weaver jar (the @Aspect/@Pointcut annotations and the pointcut EXPRESSION parser — AspectJ's language, used here only to describe joins; no compile-time weaving happens). Boot's AOP autoconfiguration then finds @Aspect beans in the context and registers them as advisors. That is the whole activation story: add the starter, define an aspect bean, proxies appear around whatever the pointcut names."""),
                        code("aop:anatomy:starter", "xml",
                                "server/pom.xml — the one dependency this topic added",
                                "server/pom.xml", """
                                <!-- Topic 06 (aop): the AspectJ weaver + @EnableAspectJAutoProxy
                                     autoconfiguration — the machinery behind the app's InvocationTraceAspect.
                                     (Boot 4 renamed this starter from the classic spring-boot-starter-aop.) -->
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-aspectj</artifactId>
                                </dependency>"""),
                        text("aop:anatomy:kinds", """
                                An aspect is a plain class annotated @Aspect that carries advice — methods Spring may invoke at join points your pointcut selects. The advice kinds form a small family: @Before runs ahead of the call; @AfterReturning runs on normal completion (and can see the return value); @AfterThrowing runs when the target threw (and can see the throwable); @After runs on the way out either way (the finally of the family); and @Around WRAPS the call entirely — your code decides when, whether, and how many times the target runs. Around is the most powerful and the easiest to get wrong, which is why ours is written against a strict production contract and is worth reading closely.

                                Our aspect marks its targets with an annotation rather than a package rule — a class opts in with @Traceable, declaring "calls into this type are part of the operational record". The marker survives renames and package moves, never sweeps up an unintended class, and reads as an explicit contract at the top of the file:"""),
                        code("aop:anatomy:traceable", "java",
                                "guide/core/aop/Traceable.java — the whole marker: runtime retention because proxy matching is a runtime question",
                                "server/src/main/java/guide/core/aop/Traceable.java", """
                                @Documented
                                @Retention(RetentionPolicy.RUNTIME)
                                @Target(ElementType.TYPE)
                                public @interface Traceable {
                                }"""),
                        code("aop:anatomy:aspect", "java",
                                "guide/core/aop/InvocationTraceAspect.java — the entire advice: all five duties of the ceremony, written once",
                                "server/src/main/java/guide/core/aop/InvocationTraceAspect.java", """
                                @Around("traceableMethods()")
                                public Object aroundTraceable(ProceedingJoinPoint joinPoint) throws Throwable {
                                    long startedAt = System.nanoTime();
                                    String method = joinPoint.getSignature().getDeclaringTypeName()
                                            + "." + joinPoint.getSignature().getName();
                                    String argsSummary = summarizeArguments(joinPoint.getArgs());
                                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                                    String outcome = OUTCOME_OK;
                                    try {
                                        return joinPoint.proceed();
                                    } catch (Throwable failure) {
                                        outcome = OUTCOME_EXCEPTION_PREFIX + failure.getClass().getSimpleName();
                                        throw failure;
                                    } finally {
                                        long durationNanos = System.nanoTime() - startedAt;
                                        invocationLog.record(new InvocationRecord(
                                                method, argsSummary, outcome, durationNanos, requestId));
                                        log.debug("invocation method={} args={} outcome={} durationNanos={} requestId={}",
                                                method, argsSummary, outcome, durationNanos, requestId);
                                    }
                                }"""),
                        text("aop:anatomy:contract", """
                                Read it as a contract, because every line is load-bearing. proceed() — the line that actually calls your use case — appears EXACTLY once, on the normal path; an advice that could call it twice (or zero times on a caching path, silently) is the classic @Around bug, and ours cannot. The catch block classifies the failure into the record's outcome and IMMEDIATELY rethrows the original — no wrapping, no swallowing, no extra log line that would collide with the error taxonomy's own handler (topic 30 owns failure logging; the aspect only records that it happened). The finally block guarantees a completed invocation always files its record — success or failure — and the duration is measured around the target only, not around the record-keeping itself.

                                The requestId comes from the logging MDC, where the RequestIdFilter (topic 04) parked it for exactly this kind of consumer: on an HTTP thread the record names its request; off it (startup, async jobs) the field is honestly null. And the debug line mirrors the record so that at debug level the same five facts appear in the log stream with the MDC-stamped request id."""),
                        text("aop:anatomy:args", """
                                One duty deserves its own hard look: rendering the arguments. A trace that copies request payloads is a liability — it doubles memory pressure, leaks personal data into logs, and can even change behavior (a huge toString inside a hot path). Ours renders at most four arguments, at most sixty characters each, summarizes collections and arrays by SIZE alone, and survives a hostile toString() that throws — because diagnostics may never break the call they diagnose:"""),
                        code("aop:anatomy:summarize", "java",
                                "guide/core/aop/InvocationTraceAspect.java — the argument summary: capped, size-based, hostile-toString-proof",
                                "server/src/main/java/guide/core/aop/InvocationTraceAspect.java", """
                                static String summarizeArguments(Object[] args) {
                                    if (args == null || args.length == 0) {
                                        return "()";
                                    }
                                    StringJoiner summary = new StringJoiner(", ", "(", ")");
                                    int shown = Math.min(args.length, MAX_ARGS);
                                    for (int index = 0; index < shown; index++) {
                                        summary.add(safeArgument(args[index]));
                                    }
                                    if (args.length > MAX_ARGS) {
                                        summary.add(args.length - MAX_ARGS + " more");
                                    }
                                    return summary.toString();
                                }"""),
                        text("aop:anatomy:log", """
                                The records land in the InvocationLog — a bounded ring of the most recent invocations, synchronized on every access because servlet threads, async executors, and virtual threads all write to it. Bounded is the operative word: an instrumentation buffer that grows with traffic is a memory leak wearing a feature's clothes. When the ring is full the oldest record is evicted; the log always holds the freshest window and nothing else:"""),
                        code("aop:anatomy:ring", "java",
                                "guide/core/aop/InvocationLog.java — record() and tail(): bounded, oldest-first, guarded",
                                "server/src/main/java/guide/core/aop/InvocationLog.java", """
                                /** Appends one record, evicting the oldest when the ring is full. */
                                public void record(InvocationRecord record) {
                                    Objects.requireNonNull(record, "record");
                                    synchronized (records) {
                                        if (records.size() == capacity) {
                                            records.pollFirst();
                                        }
                                        records.addLast(record);
                                    }
                                }

                                /** The last {@code limit} records, oldest first; fewer if the ring is shorter. */
                                public List<InvocationRecord> tail(int limit) {
                                    if (limit < 0) {
                                        throw new IllegalArgumentException("limit must be >= 0: " + limit);
                                    }
                                    synchronized (records) {
                                        int skip = Math.max(0, records.size() - limit);
                                        return records.stream().skip(skip).toList();
                                    }
                                }"""),
                        text("aop:anatomy:wiring", """
                                Finally the composition-root discipline you have seen since topic 04: the aspect is NOT @Component-scanned — it is a plain class registered as an explicit @Bean in its own configuration class, injected with its log by constructor injection, exactly like every other bean. Spring notices the @Aspect annotation wherever the bean comes from and turns it into advisors; the wiring stays honest about who constructed what:"""),
                        code("aop:anatomy:config", "java",
                                "guide/config/AopDemoConfig.java — the aspect as an explicit bean, wired like everything else",
                                "server/src/main/java/guide/config/AopDemoConfig.java", """
                                @Bean
                                InvocationLog invocationLog() {
                                    return new InvocationLog(256);
                                }

                                /** The aspect itself: plain class + @Aspect, wired by constructor injection. */
                                @Bean
                                InvocationTraceAspect invocationTraceAspect(InvocationLog invocationLog) {
                                    return new InvocationTraceAspect(invocationLog);
                                }"""),
                        demo("aop:trace:demo", "aop:trace", "The log, after two real calls",
                                "Runs one succeeding and one deliberately failing traced step through the live machinery, then shows the evidence the aspect filed for each: method, capped args summary, outcome (ok vs exception), duration in nanos, and the request id the call rode in on. Press run again — durations change, because this is measurement, not a cached page.")),
                section("pointcut-expressions", "Pointcut expressions",
                        "execution(* *(..)) is a grammar, not noise — dissected piece by piece, with the designators worth memorizing and recipes worth keeping.",
                        text("aop:pcut:anatomy", """
                                The pointcut is the WHERE of interception, written in AspectJ's expression language and compiled once at startup into a matcher. Ours, in full:

                                execution(* *(..)) && !execution(* java.lang.Object.*(..)) && @within(guide.core.aop.Traceable)

                                Take execution() apart piece by piece: execution( [modifiers] ReturnType MethodName ( Parameters ) ). Ours: any return type (*), any method name (*), any parameters ((..) — zero or more of anything; a strict list like (String, int) would demand exactly those). The wildcards compose with explicit parts: execution(* execute*(..)) — any method starting with "execute"; execution(* guide.domain.content.*.*(..)) — every method on every class in one package, the type pattern spelling exactly where to look (and .. meaning "that package and everything under it", so guide.domain..* is the whole domain tree).

                                Then the composition operators — &&, ||, ! — combine designators, and ours uses them the way real pointcuts always do: the first arm SELECTS the work, the second EXCLUDES the noise (!execution(* java.lang.Object.*(..)) keeps toString, hashCode and friends out of the trace — without it, merely rendering a proxied bean would file a record), and the third narrows by a property of the target TYPE:"""),
                        code("aop:pcut:ours", "java",
                                "guide/core/aop/InvocationTraceAspect.java — a named pointcut: selected once, referenced by the advice",
                                "server/src/main/java/guide/core/aop/InvocationTraceAspect.java", """
                                @Pointcut("execution(* *(..)) && !execution(* java.lang.Object.*(..)) && @within(guide.core.aop.Traceable)")
                                public void traceableMethods() {
                                }"""),
                        text("aop:pcut:designators", """
                                The designator families worth knowing cold. execution() matches method signatures — the finest control. within() matches CODE LOCATION: within(guide.domain..*) is every join point inside the domain tree, no signature conditions at all, and it is cheap — when Spring can prove a class cannot match, it skips proxying entirely. target() and args() match the RUNTIME type of the receiver and the arguments (args(.., String) — "whenever the last argument is a String"). The annotation family: @annotation(Foo) — the invoked METHOD carries @Foo; @within(Foo) — the declaring CLASS carries @Foo (ours); @target(Foo) — the runtime OBJECT's class carries it, subclasses included. And bean() is Spring's own addition: bean(*Controller) or bean(orderService) — match by bean NAME, the bluntest but sometimes handiest knife.

                                Recipes that come up again and again: a service layer — execution(* guide.domain..*.*(..)); an annotation-driven policy like ours — execution(* *(..)) && @within(YourMarker); "every public method of beans whose name ends in Service" — execution(* *Service.*(..)) or bean(*Service); and one METHOD precisely — execution(* guide.domain.content.GetTopicById.execute(..)). Two habits keep pointcuts healthy: name them (a @Pointcut method — as ours — states the rule once and lets several advice reference it), and prefer STRUCTURAL selectors (annotations, packages) over name patterns — renames silently break a name-based pointcut, and nothing warns you; the annotation stays glued to the class.""")),
                section("aspects-everywhere", "Aspects are already everywhere",
                        "The reveal: @Transactional, @Cacheable, @Async and @PreAuthorize are the same machinery you just met — which is why they share the same traps.",
                        text("aop:everywhere:reveal", """
                                Here is the quiet reveal that repays everything above: you have been USING aspects since your first Spring application. @Transactional opens a transaction before the method body runs and commits or rolls it back after — around advice on a pointcut that selects annotated methods (or annotated classes, exactly our @within shape). @Cacheable consults a cache INSTEAD of calling the method, and populates it after — @Around advice that may choose not to proceed() at all, the one power we deliberately did not exercise. @Async hands the invocation to an executor and returns a future immediately — advice that proceeds() on a DIFFERENT thread. @PreAuthorize evaluates a permission expression before entry — @Before advice that can throw to veto the call. None of these are language features; all of them are proxies wrapping beans, built by the same factories, selected by the same pointcut grammar, ordered by the same precedence rules.

                                Each gets its full treatment later — transactions when the SQL topics begin them in earnest (topic 17, and the JPA follow-up in 18), caching in topic 21, background execution in topic 29, method security in topic 24. What belongs to TODAY is the corollary: every one of those annotations is a promise made by a PROXY, so every proxy limitation is a limitation of the feature you thought you had."""),
                        text("aop:everywhere:pitfalls", """
                                Run the checklist against what the proxy sections taught and the "surprising" behavior of annotated beans stops being surprising. A @Transactional method called from another method of the SAME bean does not get a transaction — the internal call goes through this, the raw target, exactly the trap the next section demonstrates on our own code. A final method carrying @Cacheable is silently never cached — CGLIB cannot override it; private methods are never advised at all — subclasses cannot even see them. An @Async method must be invoked THROUGH the bean, from outside, to be async. And the proxy is invisible to the object itself: this inside your bean is never the proxy — the single fact underneath all of it.

                                Hence the diagnosis discipline: when an annotation "does nothing", ask the proxy demo's question — what object did the caller ACTUALLY hold, and did the call pass through it? getClass() on the injected bean (or the /api/demo/aop/proxy endpoint) answers in one look.""")),
                section("the-self-invocation-trap", "The self-invocation trap",
                        "A proxied bean calling its own method bypasses the proxy — constructed honestly on this app's own exhibit, and proven from the invocation log.",
                        text("aop:trap:setup", """
                                Now the trap itself, built as a teaching exhibit under domain/teaching/ — real compiled code the running demo and tests execute, because this app's genuine use cases do not (yet) call a second traced method on themselves. The exhibit is a two-step "order workflow". The interface matters to the lesson: the inner step is a PUBLIC method of a PUBLIC contract — provably proxyable — so its absence from the trace will be a proof about call paths, not an accident of visibility:"""),
                        code("aop:trap:interface", "java",
                                "guide/domain/teaching/ExhibitSelfInvocation.java — the contract: an outer step that delegates to an inner step",
                                "server/src/main/java/guide/domain/teaching/ExhibitSelfInvocation.java", """
                                public interface ExhibitSelfInvocation {

                                    String placeOrder(String item);

                                    String reserveStock(String item);
                                }"""),
                        code("aop:trap:impl", "java",
                                "guide/domain/teaching/TracedOrderWorkflow.java — @Traceable, two public steps, and the delegation that dodges the proxy",
                                "server/src/main/java/guide/domain/teaching/TracedOrderWorkflow.java", """
                                @Traceable
                                public class TracedOrderWorkflow implements ExhibitSelfInvocation {

                                    @Override
                                    public String placeOrder(String item) {
                                        // this.reserveStock(item) — a plain Java call on the raw target: the
                                        // proxy is not on this call path, so no advice runs for it.
                                        String reservation = reserveStock(item);
                                        return "order[" + item + "] ← " + reservation;
                                    }

                                    @Override
                                    public String reserveStock(String item) {
                                        return "reserved:" + item;
                                    }
                                }"""),
                        text("aop:trap:why", """
                                Read the delegation line slowly, because it is the entire topic in one statement: reserveStock(item) with no receiver is Java for this.reserveStock(item), and this inside the target object is the RAW TARGET — never the proxy. The caller's proxy intercepted placeOrder (advice ran, record filed); the delegation then happens INSIDE the target, where no proxy exists on the path. The inner method executes — its result visibly lands in the order summary — but the aspect never hears about it. Nothing is broken; something is UNSEEN.

                                The demo's use case runs the exhibit once through the injected (proxied) bean, then derives its report from whatever the log actually holds — never from assumptions about the machinery:"""),
                        code("aop:trap:usecase", "java",
                                "guide/domain/aop/ShowSelfInvocationTrap.java — the report derives from the log: present, absent, whatever the machinery did",
                                "server/src/main/java/guide/domain/aop/ShowSelfInvocationTrap.java", """
                                public Result<SelfInvocationExhibit> execute() {
                                    invocationLog.clear();
                                    String orderSummary = exhibit.placeOrder(DEMO_ITEM);
                                    List<InvocationRecord> records = invocationLog.snapshot();
                                    return Result.success(SelfInvocationExhibit.of(orderSummary, records));
                                }"""),
                        text("aop:trap:fixes", """
                                The demo below shows the live verdict — outer recorded, inner not. The fixes all route the internal call back through the proxy, and each has a price. Injecting the bean into itself (a self-reference) makes the delegation an external call — at the cost of a dependency on the machinery, visible forever in the class's fields. Splitting the steps into two beans makes every hop proxy-visible — at the cost of a class boundary that exists only for interception. Moving the orchestration OUT (one use case calling two traced beans in sequence, as our trace demo does) is the honest shape when the steps deserve independence anyway. AopContext.currentProxy() exists too — and is the one to forget first: it couples the domain to AOP internals and breaks outright when proxying is off.

                                The decision rule: when you NEED every step traced (or transactional, or cached), make the steps externally reachable and let a caller above them own the sequencing — the trap dissolves because no internal delegation remains."""),
                        demo("aop:trap:demo", "aop:self-invocation", "Outer recorded. Inner: nowhere.",
                                "Runs the order workflow through the live proxy and reports what the InvocationLog saw: the outer placeOrder call is there — method, args, outcome, duration — and the inner reserveStock, which demonstrably RAN (its result is inside the order summary), left no record at all. The report derives from the actual log, so what you see is what the machinery did.")),
                section("aspect-ordering", "Aspect ordering",
                        "Two advices on one join point run in some order — @Order states which, and with transactions the order is the semantics.",
                        text("aop:order:rules", """
                                When two aspects match the same join point, somebody runs first — and with @Around advice, "first" means OUTERMOST: it enters before the other and exits after it, wrapping it entirely. Spring's precedence rules: the LOWER @Order value wins the outer position (ordered 0 wraps ordered 10, which wraps 100); unadvised-against defaults run last (Ordered.LOWEST_PRECEDENCE — innermost); and same-valued aspects fall back to declaration order — which is nothing to build semantics on. The rules apply symmetrically on the way out, so the innermost advice's after-phases run before the outer ones'.

                                Ours declares its position explicitly even though it is currently the only aspect in the app — a one-annotation statement of intent that becomes load-bearing the day a second one lands:"""),
                        code("aop:order:ours", "java",
                                "guide/core/aop/InvocationTraceAspect.java — the position, stated",
                                "server/src/main/java/guide/core/aop/InvocationTraceAspect.java", """
                                @Aspect
                                @Order(0)
                                public class InvocationTraceAspect {"""),
                        text("aop:order:matters", """
                                When does order actually matter? Whenever one advice's work should be INSIDE or OUTSIDE another's. Trace-logging around a transaction is the canonical pair, and both placements are legitimate wants: recording OUTSIDE the transaction (our @Order(0) default) means durations include commit time and failure records exist even for calls whose transaction rolled back — what an operations timeline wants; logging INSIDE means every line is written within the unit of work — closer to audit-style requirements. @Transactional advice defaults to LOWEST_PRECEDENCE, so an @Order(0) aspect naturally wraps it. The same arithmetic governs retries around transactions (retry inside and you re-use one transaction; retry outside and each attempt gets a fresh one) and security (an authorization check usually wants to be outermost — reject before doing ANY work). Rule of thumb: make ordering explicit the moment a second advice can touch the same method, and choose by asking "whose work must observe whose?".""")),
                section("when-not-to-aop", "When not to AOP",
                        "The power is real; so is the cost. A judgment list for where interception earns its keep — and where it quietly steals from you.",
                        text("aop:not:costs", """
                                The honest costs first. HIDDEN CONTROL FLOW: an aspect is a branch every stack trace understates — the reader of GetTopicById cannot see from the file that timing and logging wrap it; behavior lives somewhere else than where it executes. That is the trade you make, and it is a good trade exactly when the behavior is OPERATIONAL rather than business — when a developer asking "why is this number wrong?" never needs to know it existed, but a developer asking "why is this slow?" can find it in one place. DEBUGGING: stepping into advised code hops through proxy frames and reflection, and a broken pointcut fails SILENTLY — nothing errors, the advice just never runs (or worse: a rename widens it and suddenly it runs too much). FRAGILITY: name-pattern pointcuts rot with refactors; annotation-driven ones (ours) cost one explicit marker per class but cannot rot silently.

                                The judgment list this guide follows: AOP EARNS its keep for operations concerns — timing, tracing, logging, transactions, caching, security checks, retries, idempotency guards: behavior that applies BROADLY, names no business rule, and belongs to whoever runs the system. EXPLICIT COMPOSITION stays better for domain logic — validation, pricing, workflow decisions: behavior a business analyst could state, which belongs where a reader of the feature finds it in place. A business rule hidden behind a pointcut is a rule nobody can audit by reading the code; the same rule as a method call is architecture as usual."""),
                        text("aop:not:ours", """
                                Applied to this repo: exactly ONE aspect exists — operational in nature, annotation-scoped, capped in what it records, and quoted in full above so the guide teaches it from its own source. The classes it traces opted in with the marker; every other use case stays a plain domain class. If a future aspect wants to make a business DECISION — veto a call, rewrite an argument, substitute a result — that is the signal to write a decorator by hand instead, name it honestly in the wiring, and let the call graph show the seam. The question is never "can AOP do this?" — it almost always can — but "should a reader have to know it does?".""")),
                section("terminal-companion", "The endpoints from a terminal",
                        "curl the three demos — the proxy verdicts, the invocation records, the trap — and turn on debug to see the aspect speak.",
                        text("aop:term:tour", """
                                The terminal closes the loop between the prose and the process. Three demos repay hand-typing: the proxy endpoint shows what the container REALLY handed out (look for the $$SpringCGLIB$$ markers in runtimeClass — that is the whole proxy story in one string); the trace endpoint runs a real success and a real failure and shows both records — compare outcome and durationNanos, and run it twice to watch durations move; the self-invocation endpoint shows the verdict of the trap section computed from the live log — outerRecorded true, innerRecorded false, and the order summary proving the inner step ran anyway. Then one more move no page can make for you: start the app with the guide's logger at debug and watch the aspect's own lines stream by, one per traced invocation, stamped with the request id the filter parked in the MDC:"""),
                        code("aop:term:curl", "bash", "the curl tour of topic 06", null, """
                                # What the container hands out — proxy verdicts for four real beans:
                                curl -s localhost:8080/api/demo/aop/proxy | head -50

                                # The aspect at work — one ok record, one exception record, both real:
                                curl -s localhost:8080/api/demo/aop/trace

                                # … run it again: durationNanos changes — live measurement, not cache:
                                curl -s localhost:8080/api/demo/aop/trace | grep durationNanos

                                # The trap, live: outerRecorded true, innerRecorded false:
                                curl -s localhost:8080/api/demo/aop/self-invocation

                                # This topic's own content, served through the layers it teaches:
                                curl -s localhost:8080/api/content/aop | head -60

                                # The aspect's debug lines — restart the app with the level lifted:
                                java -jar target/server-0.0.1-SNAPSHOT.jar --logging.level.guide=debug
                                # … then hit any demo endpoint and read the invocation lines, e.g.:
                                #   invocation method=guide.domain.aop.TracedSampleSteps.succeedingStep
                                #     args=(demo-seed) outcome=ok durationNanos=... requestId=...""")));
    }
}

package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.aop.ProxyFacts;
import guide.domain.aop.SelfInvocationExhibit;
import guide.domain.aop.ShowProxyKinds;
import guide.domain.aop.ShowSelfInvocationTrap;
import guide.domain.aop.ShowTraceEvidence;
import guide.domain.aop.TraceEvidenceExhibit;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 06 (aop) — three GETs, each thin like every
 * controller in this repo: one use case call, the Result mapped onto HTTP:
 * - GET /api/demo/aop/proxy — runtime proxy facts for this app's own wired
 *   beans: runtime class name (with proxy markers), interfaces, the
 *   JDK-vs-CGLIB verdict, and the rule that decided it
 * - GET /api/demo/aop/trace — runs one succeeding and one failing traced
 *   step, then returns the InvocationLog evidence for both: method, args
 *   summary, outcome, durationNanos
 * - GET /api/demo/aop/self-invocation — runs the ExhibitSelfInvocation
 *   workflow and reports whether the aspect saw the OUTER call, the INNER
 *   call, or both (the trap: only the outer one)
 */
@RestController
public class AopDemoController {

    private final ShowProxyKinds showProxyKinds;
    private final ShowTraceEvidence showTraceEvidence;
    private final ShowSelfInvocationTrap showSelfInvocationTrap;

    public AopDemoController(ShowProxyKinds showProxyKinds, ShowTraceEvidence showTraceEvidence,
            ShowSelfInvocationTrap showSelfInvocationTrap) {
        this.showProxyKinds = showProxyKinds;
        this.showTraceEvidence = showTraceEvidence;
        this.showSelfInvocationTrap = showSelfInvocationTrap;
    }

    /** GET /api/demo/aop/proxy → 200 proxy verdicts for this app's wired beans. */
    @GetMapping("/api/demo/aop/proxy")
    List<ProxyFacts> proxy() {
        return unwrap(showProxyKinds.execute());
    }

    /** GET /api/demo/aop/trace → 200 invocation records for a real ok + exception run. */
    @GetMapping("/api/demo/aop/trace")
    TraceEvidenceExhibit trace() {
        return unwrap(showTraceEvidence.execute());
    }

    /** GET /api/demo/aop/self-invocation → 200 the trap, proven from the log. */
    @GetMapping("/api/demo/aop/self-invocation")
    SelfInvocationExhibit selfInvocation() {
        return unwrap(showSelfInvocationTrap.execute());
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}

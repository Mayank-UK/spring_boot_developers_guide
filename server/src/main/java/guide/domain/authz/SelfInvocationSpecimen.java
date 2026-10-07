package guide.domain.authz;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The self-invocation trap, re-armed for METHOD SECURITY (topic 06
 * taught it for aspects; @PreAuthorize IS an aspect, so the trap
 * inherits). The bean is registered as itself and therefore PROXIED
 * by @EnableMethodSecurity — the injected reference callers hold is the
 * proxy, and the proxy is where the annotation runs.
 *
 * The two methods:
 * - adminOnlyNote(): @PreAuthorize'd. Called THROUGH the bean reference
 *   the check runs; bob (staff) is denied with AccessDeniedException.
 * - adminOnlyNoteFromInside(): calls adminOnlyNote() via THIS — the raw
 *   target, not the proxy — so the annotation NEVER RUNS and any caller
 *   walks through. This is not a Spring bug; it is how proxy-based AOP
 *   works, and it is why annotated security methods must only ever be
 *   entered from OUTSIDE the bean.
 */
public class SelfInvocationSpecimen {

    /** The guarded method — admin-only by annotation. */
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnlyNote() {
        return "the admin-only note (you passed a REAL @PreAuthorize check)";
    }

    /**
     * THE TRAP: an internal call to the guarded method. this.adminOnlyNote()
     * targets the raw object, bypassing the proxy and its check — bob gets
     * the note. Fix patterns live in the topic: don't self-call guarded
     * methods (split the bean), or inject the proxy (self-injection /
     * AopContext.currentProxy() with exposeProxy).
     */
    public String adminOnlyNoteFromInside() {
        return adminOnlyNote();
    }
}

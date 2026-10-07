package guide.presentation.demo.authz;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.auth.UserPrincipal;
import guide.domain.authz.ActAsGuardedAction;
import guide.domain.authz.AuthzPermissions;
import guide.domain.authz.AuthzAction;
import guide.domain.authz.EvaluateMatrix;
import guide.domain.authz.ListVisibleDocuments;
import guide.domain.authz.OwnedDocument;
import guide.domain.authz.SelfInvocationSpecimen;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 24 (authorization), the authz island:
 * - GET  /api/demo/authz/matrix                       the full verdict table,
 *                                                    rendered from the real policies
 * - POST /api/demo/authz/act-as                       switch identity (session, no
 *                                                    password — the tour's stand-in for login)
 * - GET  /api/demo/authz/documents                    only the CALLER's visible rows
 * - GET  /api/demo/authz/documents/{id}               guarded read — the verdict comes
 *                                                    from the use case's policy check
 * - GET  /api/demo/authz/documents/{id}/owner-view    the @PostAuthorize specimen (owner-only)
 * - POST /api/demo/authz/documents/{id}/publish       @PreAuthorize specimen (role SpEL +
 *                                                    @authz bean), then the use case re-checks
 * - GET  /api/demo/authz/specimens/self-invocation    the proxy-bypass trap, run live
 *
 * Three enforcement layers, three guarded shapes: the URL rule (the island's
 * authenticated() — anonymous never reaches these methods), method annotations
 * (publish, owner-view), and the in-use-case policy (read + list filtering).
 * Every response labels which layer produced its verdict; controllers carry
 * NO authorization logic of their own — annotations and labels only.
 */
@RestController
public class AuthzDemoController {

    private final EvaluateMatrix evaluateMatrix;
    private final ListVisibleDocuments listVisibleDocuments;
    private final ActAsGuardedAction actAsGuardedAction;
    private final ActAsSessions actAsSessions;
    private final SelfInvocationSpecimen selfInvocationSpecimen;

    public AuthzDemoController(EvaluateMatrix evaluateMatrix,
            ListVisibleDocuments listVisibleDocuments, ActAsGuardedAction actAsGuardedAction,
            ActAsSessions actAsSessions, SelfInvocationSpecimen selfInvocationSpecimen) {
        this.evaluateMatrix = evaluateMatrix;
        this.listVisibleDocuments = listVisibleDocuments;
        this.actAsGuardedAction = actAsGuardedAction;
        this.actAsSessions = actAsSessions;
        this.selfInvocationSpecimen = selfInvocationSpecimen;
    }

    public record ActAsRequest(String username) {
    }

    /** The @PostAuthorize specimen's return shape — owner is what the SpEL checks. */
    public record OwnerView(String id, String owner, String title, String visibility) {

        static OwnerView from(OwnedDocument document) {
            return new OwnerView(document.id(), document.ownerId(), document.title(),
                    document.visibility().wireName());
        }
    }

    // ── open doors: the tour starts anonymous ──────────────────────────────────

    @GetMapping(value = "/api/demo/authz/matrix", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> matrix() {
        EvaluateMatrix.MatrixView view = evaluateMatrix.execute();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("actions", view.actions());
        response.put("roleMatrix", view.roleMatrix());
        response.put("users", view.users());
        response.put("documents", distinctDocuments(view.rows()));
        response.put("rows", view.rows());
        return response;
    }

    @PostMapping(value = "/api/demo/authz/act-as", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> actAs(@RequestBody ActAsRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserPrincipal user = actAsSessions.actAs(request.username(), httpRequest, httpResponse);
        return Map.of(
                "user", userView(user),
                "via", "session (act-as)",
                "passwordless", true,
                "session", Map.of(
                        "fixationProtected", true,
                        "note", "session glue reused from topic 23 — identity switching for the "
                                + "tour; real deployments never switch identity without proof"));
    }

    // ── guarded: the three enforcement layers ─────────────────────────────────

    /** Layer: in-use-case policy — the FILTER shape (the query is the guard). */
    @GetMapping(value = "/api/demo/authz/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> documents() {
        UserPrincipal caller = currentCaller();
        List<Map<String, Object>> visible = listVisibleDocuments.execute(caller).stream()
                .map(row -> documentView(row.document(), row.visibleBecause(), row.checkedBy()))
                .toList();
        return Map.of(
                "caller", userView(caller),
                "enforcedBy", "in-use-case policy (OwnershipPolicy filters the rows)",
                "documents", visible);
    }

    /** Layer: in-use-case policy — the CHECK shape (deny → 403 envelope, not 500). */
    @GetMapping(value = "/api/demo/authz/documents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> document(@PathVariable String id) {
        UserPrincipal caller = currentCaller();
        return switch (actAsGuardedAction.execute(caller, AuthzAction.READ.wireName(), id)) {
            case Success<ActAsGuardedAction.GuardedOutcome> success -> {
                ActAsGuardedAction.GuardedOutcome outcome = success.value();
                yield Map.of(
                        "caller", userView(caller),
                        "enforcedBy", "in-use-case policy (DenyByDefault → OwnershipPolicy)",
                        "checkedBy", outcome.checkedBy(),
                        "reason", outcome.reason(),
                        "document", documentView(outcome.document(), outcome.reason(),
                                outcome.checkedBy()));
            }
            case Failed<ActAsGuardedAction.GuardedOutcome> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    /**
     * Layer: method security, the @PostAuthorize specimen — check AFTER fetch,
     * on the RETURN VALUE: returnObject.owner() == authentication.name. This
     * route's only guard is the annotation (the fetch is deliberately raw —
     * peek()); it is blunter than the policy on purpose: an ADMIN fails it on
     * a stranger's document too, which is exactly what a pure ownership check
     * says. Denial → AccessDeniedException → the authz advice's 403 envelope.
     */
    @GetMapping(value = "/api/demo/authz/documents/{id}/owner-view",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PostAuthorize("returnObject.owner() == authentication.name")
    public OwnerView ownerView(@PathVariable String id) {
        return switch (actAsGuardedAction.peek(id)) {
            case Success<OwnedDocument> success -> OwnerView.from(success.value());
            case Failed<OwnedDocument> failed -> throw AppException.of(failed.failure());
        };
    }

    /**
     * Layer: method security, the @PreAuthorize specimen — role SpEL gate
     * (hasAnyRole) composed with a BEAN-REFERENCED permission check
     * (@authz.canPublish: real OwnershipPolicy, not inline string logic).
     * carol dies at the role half; bob dies at the bean half; alice passes
     * both (owner AND admin). The use case re-checks and performs — defense
     * in depth: the annotation guards this ROUTE, the policy guards the
     * ACTION wherever it is invoked from.
     */
    @PostMapping(value = "/api/demo/authz/documents/{id}/publish",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF') and @authz.canPublish(#id, authentication)")
    public Map<String, Object> publish(@PathVariable String id) {
        UserPrincipal caller = currentCaller();
        return switch (actAsGuardedAction.execute(caller, AuthzAction.PUBLISH.wireName(), id)) {
            case Success<ActAsGuardedAction.GuardedOutcome> success -> {
                ActAsGuardedAction.GuardedOutcome outcome = success.value();
                yield Map.of(
                        "caller", userView(caller),
                        "enforcedBy", "method security (@PreAuthorize: role SpEL + @authz bean), "
                                + "then the use case's policy re-check",
                        "checkedBy", outcome.checkedBy(),
                        "effect", outcome.effect(),
                        "document", documentView(outcome.document(), outcome.reason(),
                                outcome.checkedBy()));
            }
            case Failed<ActAsGuardedAction.GuardedOutcome> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    /** The self-invocation trap, run live as the current caller. */
    @GetMapping(value = "/api/demo/authz/specimens/self-invocation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> selfInvocationSpecimen() {
        UserPrincipal caller = currentCaller();
        boolean directAllowed;
        try {
            selfInvocationSpecimen.adminOnlyNote();
            directAllowed = true;
        } catch (AccessDeniedException denied) {
            directAllowed = false;
        }
        // the trap: this internal call goes through this., not the proxy —
        // the @PreAuthorize never runs, whatever the caller's roles
        String leakedNote = selfInvocationSpecimen.adminOnlyNoteFromInside();
        return Map.of(
                "caller", userView(caller),
                "guardedMethod", "@PreAuthorize(\"hasRole('ADMIN')\")",
                "directCall", Map.of("checked", true, "allowed", directAllowed),
                "internalCall", Map.of("checked", false, "allowed", true, "returned", leakedNote),
                "trap", "the internal call targeted this., not the proxy — the check never ran "
                        + "(topic 06's trap, re-armed: @PreAuthorize is an aspect)");
    }

    // ── presentation shaping only ──────────────────────────────────────────────

    /** The caller as one domain principal, whichever mechanism authenticated. */
    private static UserPrincipal currentCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal() instanceof String) {
            throw new AppException(Failure.of(FailureKind.UNAUTHORIZED,
                    "no authenticated principal — the chain's url rule should have answered 401"));
        }
        return AuthzPermissions.asPrincipal(authentication);
    }

    private static Map<String, Object> userView(UserPrincipal user) {
        return Map.of("id", user.id(), "username", user.username(),
                "roles", List.copyOf(user.roles()));
    }

    private static Map<String, Object> documentView(OwnedDocument document, String reason,
            String checkedBy) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", document.id());
        view.put("title", document.title());
        view.put("owner", document.ownerId());
        view.put("visibility", document.visibility().wireName());
        view.put("body", document.body());
        view.put("reason", reason);
        view.put("checkedBy", checkedBy);
        return view;
    }

    private static List<Map<String, Object>> distinctDocuments(
            List<EvaluateMatrix.MatrixRow> rows) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (EvaluateMatrix.MatrixRow row : rows) {
            byId.computeIfAbsent(row.documentId(), id -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", row.documentId());
                view.put("title", row.documentTitle());
                view.put("owner", row.documentOwner());
                view.put("visibility", row.visibility());
                return view;
            });
        }
        return List.copyOf(byId.values());
    }
}

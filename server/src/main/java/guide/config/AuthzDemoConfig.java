package guide.config;

import guide.data.authz.InMemoryDocumentStore;
import guide.domain.authz.ActAsGuardedAction;
import guide.domain.authz.AuthzPermissions;
import guide.domain.authz.DenyByDefault;
import guide.domain.authz.DocumentStore;
import guide.domain.authz.EvaluateMatrix;
import guide.domain.authz.ListVisibleDocuments;
import guide.domain.authz.OwnershipPolicy;
import guide.domain.authz.RolePolicy;
import guide.domain.authz.SelfInvocationSpecimen;
import guide.presentation.demo.authz.ActAsSessions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Demo wiring for topic 24 (authorization) — the authz island's use
 * cases as explicit beans over the SAME identity substrate topic 23
 * seeded (one IdentityStore bean, shared: the tour's alice/bob/carol
 * are the same users the auth flows authenticate).
 *
 * Two choices worth reading twice:
 * - @EnableMethodSecurity LIVES HERE, not on SecurityConfig: the URL
 *   baseline (topic 23) and the annotation machinery (topic 24) are
 *   separable decisions — the guide switches the second on exactly
 *   when the topic that teaches it lands.
 * - the AuthzPermissions bean is registered under the NAME "authz"
 *   because method-security SpEL addresses beans BY NAME:
 *   @PreAuthorize("@authz.canPublish(#id, authentication)") — the
 *   annotation and this registration meet at that string, deliberately.
 */
@Configuration
@EnableMethodSecurity
public class AuthzDemoConfig {

    @Bean
    DocumentStore documentStore() {
        return new InMemoryDocumentStore();
    }

    @Bean
    RolePolicy rolePolicy() {
        return RolePolicy.seeded();
    }

    @Bean
    OwnershipPolicy ownershipPolicy() {
        return new OwnershipPolicy();
    }

    @Bean
    DenyByDefault denyByDefault(RolePolicy rolePolicy, OwnershipPolicy ownershipPolicy) {
        return new DenyByDefault(rolePolicy, ownershipPolicy);
    }

    @Bean
    EvaluateMatrix evaluateMatrix(guide.domain.auth.IdentityStore identityStore,
            DocumentStore documentStore, RolePolicy rolePolicy, DenyByDefault evaluator) {
        return new EvaluateMatrix(identityStore, documentStore, rolePolicy, evaluator);
    }

    @Bean
    ListVisibleDocuments listVisibleDocuments(DocumentStore documentStore,
            OwnershipPolicy ownershipPolicy) {
        return new ListVisibleDocuments(documentStore, ownershipPolicy);
    }

    @Bean
    ActAsGuardedAction actAsGuardedAction(DocumentStore documentStore, DenyByDefault evaluator) {
        return new ActAsGuardedAction(documentStore, evaluator);
    }

    /** Named "authz" — the string method-security SpEL references. */
    @Bean(name = "authz")
    AuthzPermissions authzPermissions(DocumentStore documentStore, OwnershipPolicy ownershipPolicy) {
        return new AuthzPermissions(documentStore, ownershipPolicy);
    }

    /** The self-invocation specimen, as a bean so the proxying machinery proxies it. */
    @Bean
    SelfInvocationSpecimen selfInvocationSpecimen() {
        return new SelfInvocationSpecimen();
    }

    /**
     * The tour's identity switcher: session glue that logs the demo IN as
     * one of the seeded users without a password — authorization assumes
     * authentication happened; the switcher is its stand-in. Built on the
     * SAME UserDetailsService and session machinery topic 23 established.
     */
    @Bean
    ActAsSessions actAsSessions(guide.domain.auth.IdentityStore identityStore,
            UserDetailsService userDetailsService) {
        return new ActAsSessions(identityStore, userDetailsService);
    }
}

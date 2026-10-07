package guide.domain.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.data.authz.InMemoryDocumentStore;
import guide.domain.auth.UserPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The authz policies as pure logic: the seeded role matrix for the three
 * demo users, ownership precedence (owner → admin → team+visibility),
 * the deny-by-default verdicts (unknown actions deny EVEN FOR ADMIN),
 * and the use-case-layer list filtering over the real seeded store.
 * These are the facts the matrix endpoint renders and the guarded
 * endpoints enforce — asserted here without any Spring machinery.
 */
class AuthzPoliciesTest {

    private final RolePolicy roles = RolePolicy.seeded();
    private final OwnershipPolicy ownership = new OwnershipPolicy();
    private final DenyByDefault evaluator = new DenyByDefault(roles, ownership);

    private final UserPrincipal alice = new UserPrincipal("usr_alice", "alice",
            List.of("admin", "staff"), "hash");
    private final UserPrincipal bob = new UserPrincipal("usr_bob", "bob",
            List.of("staff"), "hash");
    private final UserPrincipal carol = new UserPrincipal("usr_carol", "carol",
            List.of("reader"), "hash");

    private final OwnedDocument alicePrivate = new OwnedDocument("doc-a1", "alice",
            "t", "b", OwnedDocument.Visibility.PRIVATE);
    private final OwnedDocument aliceTeam = new OwnedDocument("doc-a2", "alice",
            "t", "b", OwnedDocument.Visibility.TEAM);
    private final OwnedDocument bobPrivate = new OwnedDocument("doc-b1", "bob",
            "t", "b", OwnedDocument.Visibility.PRIVATE);
    private final OwnedDocument publicDoc = new OwnedDocument("doc-p1", "carol",
            "t", "b", OwnedDocument.Visibility.PUBLIC);

    @Test
    void theSeededMatrixIsCorrectForTheThreeUsers() {
        assertTrue(roles.anyRoleAllows(alice.roles(), AuthzAction.PUBLISH), "admin publishes");
        assertTrue(roles.anyRoleAllows(bob.roles(), AuthzAction.PUBLISH), "staff publishes");
        assertFalse(roles.anyRoleAllows(carol.roles(), AuthzAction.PUBLISH), "reader never publishes");
        assertTrue(roles.anyRoleAllows(carol.roles(), AuthzAction.READ), "reader reads");
        assertTrue(roles.anyRoleAllows(carol.roles(), AuthzAction.LIST), "reader lists");
        assertFalse(roles.roleAllows("intern", AuthzAction.READ), "unknown role denies");
        assertEquals(List.of("reader", "staff", "admin"), roles.knownRoles(), "declaration order");
    }

    @Test
    void ownershipEnforcement_bobCannotReadAlicesPrivate_adminCan() {
        assertFalse(ownership.canRead(bob, alicePrivate), "bob is neither owner nor admin");
        assertTrue(ownership.canRead(alice, alicePrivate), "owner");
        assertTrue(ownership.canRead(alice, bobPrivate), "admin escape hatch");
        assertTrue(ownership.canRead(bob, aliceTeam), "team visibility + staff role");
        assertFalse(ownership.canRead(carol, aliceTeam), "team visibility needs the team role");
        assertTrue(ownership.canRead(carol, publicDoc), "public is any-authenticated");
    }

    @Test
    void publishIsOwnerOrAdmin_evenThoughTheRoleMatrixLetsStaffPublish() {
        // the layering lesson: the ROLE axis says staff may publish;
        // the OBJECT axis still refuses bob on alice's row
        assertTrue(roles.anyRoleAllows(bob.roles(), AuthzAction.PUBLISH));
        assertFalse(ownership.canPublish(bob, alicePrivate));
        assertTrue(ownership.canPublish(alice, bobPrivate), "admin");
        assertTrue(ownership.canPublish(bob, bobPrivate), "owner");
        // carol OWNS publicDoc — ownership alone would allow her to publish it;
        // the ROLE gate denies first, because composition is deny-dominant
        assertTrue(ownership.canPublish(carol, publicDoc), "owner — ownership alone would allow");
        assertFalse(evaluator.decide(carol, "publish", publicDoc).allowed(),
                "but the role gate denies reader first (no role of carol allows 'publish')");
    }

    @Test
    void denyByDefault_unknownActionsDenyEvenForAdmin() {
        DenyByDefault.Verdict unknown = evaluator.decide(alice, "exfiltrate", alicePrivate);
        assertFalse(unknown.allowed());
        assertEquals("deny-by-default", unknown.checkedBy());
        assertTrue(unknown.reason().contains("unknown action"));

        DenyByDefault.Verdict roleDenied = evaluator.decide(carol, "publish", publicDoc);
        assertFalse(roleDenied.allowed());
        assertEquals("role-policy", roleDenied.checkedBy());

        DenyByDefault.Verdict objectDenied = evaluator.decide(bob, "publish", alicePrivate);
        assertFalse(objectDenied.allowed());
        assertEquals("ownership-policy", objectDenied.checkedBy());

        DenyByDefault.Verdict allowed = evaluator.decide(bob, "read", aliceTeam);
        assertTrue(allowed.allowed());
        assertEquals("ownership-policy", allowed.checkedBy());
    }

    @Test
    void listVisibleDocumentsFiltersPerCallerOverTheRealSeed() {
        ListVisibleDocuments list = new ListVisibleDocuments(new InMemoryDocumentStore(), ownership);

        List<String> aliceSees = list.execute(alice).stream()
                .map(row -> row.document().id()).toList();
        assertEquals(List.of("doc-alice-private", "doc-alice-team", "doc-bob-private",
                "doc-bob-team", "doc-carol-private", "doc-roadmap"), aliceSees,
                "admin sees everything");

        List<String> bobSees = list.execute(bob).stream()
                .map(row -> row.document().id()).toList();
        assertEquals(List.of("doc-alice-team", "doc-bob-private", "doc-bob-team", "doc-roadmap"),
                bobSees, "bob: own + team-shared + public — never others' private");

        List<String> carolSees = list.execute(carol).stream()
                .map(row -> row.document().id()).toList();
        assertEquals(List.of("doc-carol-private", "doc-roadmap"), carolSees,
                "carol: own + public — team-shared needs the staff role");

        assertTrue(list.execute(bob).stream()
                .allMatch(row -> row.checkedBy().equals("ownership-policy")));
    }

    @Test
    void publishMutatesVisibilityThroughTheGuardedAction() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        ActAsGuardedAction action = new ActAsGuardedAction(store, evaluator);

        assertTrue(action.execute(bob, "publish", "doc-bob-private").isSuccess());
        assertEquals(OwnedDocument.Visibility.PUBLIC, store.findById("doc-bob-private")
                .orElseThrow().visibility(), "publish flips visibility to public");
        assertFalse(ownership.canRead(carol, new OwnedDocument("x", "bob", "t", "b",
                OwnedDocument.Visibility.PRIVATE)), "sanity: private still closed to carol");

        assertTrue(action.execute(bob, "publish", "doc-bob-private").isSuccess(),
                "publish is idempotent on an already-public doc");
        assertFalse(action.execute(bob, "publish", "doc-alice-private").isSuccess(),
                "bob cannot publish alice's doc");
        assertFalse(action.execute(bob, "read", "doc-alice-private").isSuccess(),
                "bob cannot read alice's private doc");
        assertTrue(action.execute(alice, "read", "doc-alice-private").isSuccess(),
                "owner reads own private doc");
    }
}

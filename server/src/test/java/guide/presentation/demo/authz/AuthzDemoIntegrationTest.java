package guide.presentation.demo.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The authz island against the REAL chain (full context — the @WebMvcTest
 * slices bypass security by design, gotcha #7): the URL-rule layer refusing
 * anonymous requests, the act-as session switch and its rotation, the
 * matrix endpoint rendering the live policies, the three guarded shapes
 * (in-use-case policy read, @PreAuthorize publish, @PostAuthorize
 * owner-view) with every 403 arriving as THIS app's error envelope —
 * code forbidden + requestId, never Spring's default body — the list
 * re-filtering per caller, and the self-invocation trap, asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthzDemoIntegrationTest {

    private static final String MATRIX = "/api/demo/authz/matrix";
    private static final String ACT_AS = "/api/demo/authz/act-as";
    private static final String DOCUMENTS = "/api/demo/authz/documents";
    private static final String SELF_INVOCATION = "/api/demo/authz/specimens/self-invocation";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theIslandsOpenDoorsAnswerAnonymousButTheGuardedSurfaceRefusesIt() throws Exception {
        // the URL-rule layer: 401 before any controller runs
        mockMvc.perform(get(DOCUMENTS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(DOCUMENTS + "/doc-alice-private")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(DOCUMENTS + "/doc-bob-private/publish"))
                .andExpect(status().isUnauthorized());
        // the open doors: the reference table and the identity switch
        mockMvc.perform(get(MATRIX)).andExpect(status().isOk());
    }

    @Test
    void actAsEstablishesASessionAndSwitchingRotatesIt() throws Exception {
        MvcResult asAlice = mockMvc.perform(post(ACT_AS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.roles[0]").value("admin"))
                .andExpect(jsonPath("$.via").value("session (act-as)"))
                .andExpect(jsonPath("$.session.fixationProtected").value(true))
                .andReturn();
        MockHttpSession aliceSession = (MockHttpSession) asAlice.getRequest().getSession();
        String idAfterAliceLogin = aliceSession.getId();

        mockMvc.perform(get(DOCUMENTS).session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller.username").value("alice"));

        // switch identity ON THE SAME SESSION: the id must rotate (fixation
        // protection — MockHttpServletRequest changes the id on the SAME session
        // object, so the comparison needs the id captured BEFORE the switch)
        MvcResult asBob = mockMvc.perform(post(ACT_AS).session(aliceSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession bobSession = (MockHttpSession) asBob.getRequest().getSession(false);
        assertThat(bobSession.getId()).isNotEqualTo(idAfterAliceLogin);
        mockMvc.perform(get(DOCUMENTS).session(bobSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller.username").value("bob"));

        // unknown users are a not_found in the envelope, not a 500
        mockMvc.perform(post(ACT_AS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.message").value(
                        "unknown user 'ghost' — act-as switches between alice, bob, carol"));
    }

    @Test
    void theMatrixIsRenderedFromTheRealPolicies() throws Exception {
        mockMvc.perform(get(MATRIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleMatrix[0].role").value("reader"))
                .andExpect(jsonPath("$.roleMatrix[0].allowedActions.length()").value(2))
                .andExpect(jsonPath("$.roleMatrix[1].role").value("staff"))
                .andExpect(jsonPath("$.roleMatrix[1].allowedActions.length()").value(3))
                .andExpect(jsonPath("$.users.length()").value(3))
                .andExpect(jsonPath("$.rows.length()").value(54)) // 3 users × 3 actions × 6 docs
                // computed verdicts, not hardcoded ones: carol may never publish
                // (six deny rows), alice (admin) may read every row, bob may not
                // read alice's private doc — JsonPath booleans compare by value,
                // never by bare truthiness
                .andExpect(jsonPath("$.rows[?(@.username == 'carol' && @.action == 'publish'"
                        + " && @.allowed == false)]").isNotEmpty())
                .andExpect(jsonPath("$.rows[?(@.username == 'carol' && @.action == 'publish'"
                        + " && @.allowed == true)]").isEmpty())
                .andExpect(jsonPath("$.rows[?(@.username == 'alice' && @.action == 'read'"
                        + " && @.allowed == false)]").isEmpty())
                .andExpect(jsonPath("$.rows[?(@.username == 'bob' && @.documentId == 'doc-alice-private'"
                        + " && @.action == 'read' && @.allowed == true)]").isEmpty());
    }

    @Test
    void theListRefiltersPerCaller_TheQueryShapesAuthzLesson() throws Exception {
        MockHttpSession bob = actAs("bob");
        mockMvc.perform(get(DOCUMENTS).session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforcedBy").value(
                        "in-use-case policy (OwnershipPolicy filters the rows)"))
                .andExpect(jsonPath("$.documents.length()").value(4))
                .andExpect(jsonPath("$.documents[?(@.id == 'doc-alice-private')]").isEmpty())
                .andExpect(jsonPath("$.documents[?(@.id == 'doc-carol-private')]").isEmpty())
                .andExpect(jsonPath("$.documents[?(@.id == 'doc-alice-team')]").isNotEmpty());

        MockHttpSession carol = actAs("carol");
        mockMvc.perform(get(DOCUMENTS).session(carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[?(@.id == 'doc-carol-private')]").isNotEmpty())
                .andExpect(jsonPath("$.documents[?(@.id == 'doc-roadmap')]").isNotEmpty());
    }

    @Test
    void aGuardedReadDenialIsOurErrorEnvelope_codeReasonAndLayer() throws Exception {
        MockHttpSession bob = actAs("bob");
        // bob cannot read alice's private document — and the 403 is THIS app's
        // envelope, not Spring Security's default body
        mockMvc.perform(get(DOCUMENTS + "/doc-alice-private").session(bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.checkedBy").value("ownership-policy"))
                .andExpect(jsonPath("$.error.message").value(
                        "private to alice (and admins) — caller is neither"));

        // the admin escape hatch: alice reads bob's private row
        MockHttpSession alice = actAs("alice");
        mockMvc.perform(get(DOCUMENTS + "/doc-bob-private").session(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedBy").value("ownership-policy"))
                .andExpect(jsonPath("$.document.owner").value("bob"));

        // unknown document: not_found, the taxonomy's routing-shaped answer
        mockMvc.perform(get(DOCUMENTS + "/doc-nope").session(bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void publishDenialsComeFromTheAnnotationLayer_inOurEnvelope() throws Exception {
        // carol (reader): the ROLE half of @PreAuthorize denies
        MockHttpSession carol = actAs("carol");
        mockMvc.perform(post(DOCUMENTS + "/doc-carol-private/publish").session(carol))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.message").value(
                        "access denied by method security — the @PreAuthorize/@PostAuthorize "
                                + "verdict was deny"));

        // bob (staff, not owner): the BEAN half denies — hasAnyRole passed,
        // @authz.canPublish refused
        MockHttpSession bob = actAs("bob");
        mockMvc.perform(post(DOCUMENTS + "/doc-alice-private/publish").session(bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    @Test
    @DirtiesContext // publish mutates the shared in-memory substrate — restore the seeds after
    void publishAsAdminChangesPolicyStateAndBobCanThenReadIt() throws Exception {
        MockHttpSession alice = actAs("alice");
        mockMvc.perform(post(DOCUMENTS + "/doc-bob-private/publish").session(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforcedBy").value(
                        "method security (@PreAuthorize: role SpEL + @authz bean), "
                                + "then the use case's policy re-check"))
                .andExpect(jsonPath("$.effect").value(
                        "visibility private→public — now readable by all"))
                .andExpect(jsonPath("$.document.visibility").value("public"));

        // policy state CHANGED: bob's own row is public now — and carol can read it too
        MockHttpSession carol = actAs("carol");
        mockMvc.perform(get(DOCUMENTS + "/doc-bob-private").session(carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.visibility").value("public"));
    }

    @Test
    void thePostAuthorizeSpecimenIsBlunterThanPolicy_adminStillDenied() throws Exception {
        // alice owns her row: the returnObject check passes
        MockHttpSession alice = actAs("alice");
        mockMvc.perform(get(DOCUMENTS + "/doc-alice-private/owner-view").session(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("alice"));

        // bob on alice's row: denied, in OUR envelope
        MockHttpSession bob = actAs("bob");
        mockMvc.perform(get(DOCUMENTS + "/doc-alice-private/owner-view").session(bob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());

        // the lesson: alice is ADMIN (the policy would allow reading bob's team
        // doc) — but pure ownership is ALL the annotation says, so she fails too
        mockMvc.perform(get(DOCUMENTS + "/doc-bob-team/owner-view").session(alice))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));
    }

    @Test
    void theSelfInvocationTrapIsRealAndAsserted_bobWalksThroughTheInternalCall() throws Exception {
        MockHttpSession session = actAs("bob");
        mockMvc.perform(get(SELF_INVOCATION).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller.username").value("bob"))
                .andExpect(jsonPath("$.directCall.checked").value(true))
                .andExpect(jsonPath("$.directCall.allowed").value(false))
                .andExpect(jsonPath("$.internalCall.checked").value(false))
                .andExpect(jsonPath("$.internalCall.allowed").value(true));
    }

    private MockHttpSession actAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post(ACT_AS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }
}

package guide.presentation.demo.auth;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The session island against the real chain: login writes an
 * authenticated SecurityContext INTO the session (with fixation
 * protection — the id changes on re-login), me reads it back on
 * subsequent requests that carry the cookie, logout invalidates the
 * whole session. MockMvc carries the session across requests the way a
 * browser carries the cookie.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthSessionsDemoTest {

    private static final String LOGIN = "/api/demo/auth/session/login";
    private static final String ME = "/api/demo/auth/session/me";
    private static final String LOGOUT = "/api/demo/auth/session/logout";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginAuthenticatesAliceAndSurfacesTheCookieAttributes() throws Exception {
        // NOTE: MockMvc runs without a real container, so the JSESSIONID Set-Cookie
        // header is not materialized here — the cookie's CONFIGURED attributes are
        // surfaced in the response body instead (what the demo shows), and the
        // config keys themselves are quoted in the topic's cookies section.
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.roles[0]").value("admin"))
                .andExpect(jsonPath("$.user.roles.length()").value(2))
                .andExpect(jsonPath("$.via").value("session"))
                .andExpect(jsonPath("$.session.cookie.httpOnly").value(true))
                .andExpect(jsonPath("$.session.cookie.sameSite").value("lax"))
                .andExpect(jsonPath("$.session.cookie.name").value("JSESSIONID"))
                .andExpect(jsonPath("$.session.fixationProtected").value(true));
    }

    @Test
    void wrongPasswordIsOneUnauthorizedShapeForBothFailureModes() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"))
                .andExpect(jsonPath("$.error.message").value("invalid credentials"));
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("invalid credentials"));
    }

    @Test
    void meReadsThePrincipalBackFromTheSessionCookie() throws Exception {
        MvcResult login = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"bob-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        mockMvc.perform(get(ME).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("bob"))
                .andExpect(jsonPath("$.user.roles[0]").value("staff"))
                .andExpect(jsonPath("$.via").value("session"));
    }

    @Test
    void meWithoutASessionIsRefusedByTheChainWith401() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRotatesTheSessionIdOfAnExistingSession() throws Exception {
        // a session exists BEFORE login (an anonymous, cart-shaped session): its id
        // must DIE at login — attributes survive, the id does not
        MockHttpSession preLogin = new MockHttpSession();
        String idBefore = preLogin.getId();

        MvcResult login = mockMvc.perform(post(LOGIN)
                        .session(preLogin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String idAfter = login.getRequest().getSession(false).getId();
        assertThat(idAfter).isNotEqualTo(idBefore);
        // and the re-issued session still authenticates
        mockMvc.perform(get(ME).session((MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("alice"));
    }

    @Test
    void logoutInvalidatesTheSessionForSubsequentRequests() throws Exception {
        MvcResult login = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"carol-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        mockMvc.perform(post(LOGOUT).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedOut").value(true));

        mockMvc.perform(get(ME).session(session))
                .andExpect(status().isUnauthorized());
    }
}

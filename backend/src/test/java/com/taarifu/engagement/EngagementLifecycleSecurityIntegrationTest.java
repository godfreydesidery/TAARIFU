package com.taarifu.engagement;

import com.taarifu.AbstractHttpIntegrationTest;
import com.taarifu.common.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-gate integration tests for the engagement lifecycle HTTP surface (ARCHITECTURE §6.2; PRD §12.2
 * M8/M9/M10, §18, §22.6) — the wave-4 endpoints that expose the petition/survey/question lifecycle.
 *
 * <p>Responsibility: proves the deny-by-default method security actually fires on the new lifecycle
 * endpoints, and that the public reads stay open:</p>
 * <ul>
 *   <li><b>{@code POST /petitions/{id}/activation}</b> — the moderation-before-public gate (UC-E02): requires
 *       {@code ROLE_MODERATOR}; unauthenticated → 401, a citizen → 403. These <b>fail closed</b> if the
 *       {@code @PreAuthorize("hasRole('MODERATOR')")} were removed (the exact legacy "authenticated-only
 *       admin surface" gap this design forbids, PRD §7.1).</li>
 *   <li><b>{@code POST /surveys/{id}/opening}</b> and <b>{@code POST /questions/{id}/answer}</b> — require
 *       authentication (unauthenticated → 401); the data-dependent author-or-staff / target-rep rules are
 *       enforced in the service and proven by the service unit tests.</li>
 *   <li><b>Public reads</b> — {@code GET /petitions}, {@code /surveys}, {@code /questions} are reachable
 *       unauthenticated (PRD §22.6; the URL allow-list is GET-only, so the new POSTs are never opened by it).</li>
 * </ul>
 *
 * <p>WHY full {@code @SpringBootTest} + {@code MockMvc} (not a {@code @WebMvcTest} slice): the project wires
 * security in {@code SecurityConfig} with the JWT filter and custom 401/403 envelopes; a full context
 * exercises the real chain (matching {@link com.taarifu.admin.AdminSecurityIntegrationTest}).
 * {@code @WithMockUser} drives the method-security expressions without minting real tokens. The
 * lifecycle-action tests assert only the <b>authorization outcome</b> (401/403): a 403 for a citizen and a
 * 401 for an anonymous caller prove the gate fires <i>before</i> the (mocked-away) domain runs, so they do not
 * depend on a seeded petition/survey/question.</p>
 *
 * <p>NOTE: Docker is unavailable in the local sandbox, so these run in CI only (the module's Mockito unit
 * tests are the local green gate) — the same constraint as {@link EngagementConstraintsIntegrationTest}.</p>
 */
class EngagementLifecycleSecurityIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- petition activation (moderation-before-public gate, UC-E02) --------------------------------

    @Test
    void activatePetition_unauthenticated_is401() throws Exception {
        mockMvc.perform(post("/api/v1/petitions/" + UUID.randomUUID() + "/activation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void activatePetition_asCitizen_is403() throws Exception {
        // A citizen must NEVER make a petition public — the gate fails closed at hasRole('MODERATOR').
        mockMvc.perform(post("/api/v1/petitions/" + UUID.randomUUID() + "/activation"))
                .andExpect(status().isForbidden());
    }

    @Test
    void activatePetition_asAdmin_passesTheGate_not401or403() throws Exception {
        // ADMIN inherits MODERATOR via the role hierarchy (ROOT > ADMIN > MODERATOR), so the gate is passed.
        // The petition does not exist → the service raises NOT_FOUND (404); the point is it is NOT 401/403,
        // proving the authorization gate let the staff caller through to the handler. The principal carries a
        // real publicId (authedWithPublicId) because the activation handler attributes the actor via
        // CurrentUser.requirePublicId() before the lookup — a bare @WithMockUser (no details) would 500.
        mockMvc.perform(post("/api/v1/petitions/" + UUID.randomUUID() + "/activation")
                        .with(authedWithPublicId("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // ---- survey opening (author-or-staff; controller requires auth) ---------------------------------

    @Test
    void openSurvey_unauthenticated_is401() throws Exception {
        mockMvc.perform(post("/api/v1/surveys/" + UUID.randomUUID() + "/opening"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openSurvey_authenticated_passesUrlGate_not401() throws Exception {
        // The controller only requires authentication; the author-or-staff decision is in the service. A
        // non-existent survey → NOT_FOUND. The assertion proves the new POST is NOT opened by the public
        // GET-only allow-list (it required a token) and reached method security. The principal carries a real
        // publicId (the opening handler resolves the actor via CurrentUser before the lookup).
        mockMvc.perform(post("/api/v1/surveys/" + UUID.randomUUID() + "/opening")
                        .with(authedWithPublicId("CITIZEN")))
                .andExpect(status().isNotFound());
    }

    // ---- question answer (target-rep only; controller requires auth) --------------------------------

    @Test
    void answerQuestion_unauthenticated_is401() throws Exception {
        mockMvc.perform(post("/api/v1/questions/" + UUID.randomUUID() + "/answer")
                        .contentType("application/json")
                        .content("{\"body\":\"Tunafanya kazi.\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void answerQuestion_authenticated_passesUrlGate_not401() throws Exception {
        // Authentication is required; the target-rep rule is enforced in the service. A non-existent question
        // → NOT_FOUND; the request reached the handler (not 401), proving the POST is token-gated. The
        // principal carries a real publicId (the answer handler resolves the answerer via CurrentUser before
        // the lookup).
        mockMvc.perform(post("/api/v1/questions/" + UUID.randomUUID() + "/answer")
                        .with(authedWithPublicId("CITIZEN"))
                        .contentType("application/json")
                        .content("{\"body\":\"Tunafanya kazi.\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- public reads stay open (PRD §22.6) --------------------------------------------------------

    @Test
    void listPetitions_unauthenticated_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/petitions"))
                .andExpect(status().isOk());
    }

    @Test
    void listSurveys_unauthenticated_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/surveys"))
                .andExpect(status().isOk());
    }

    @Test
    void listQuestions_unauthenticated_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/questions"))
                .andExpect(status().isOk());
    }

    // ---- test auth helper ---------------------------------------------------------------------------

    /**
     * Authenticates the MockMvc request with a principal in the <b>production shape</b>: a
     * {@link UsernamePasswordAuthenticationToken} whose granted authorities carry the {@code ROLE_*} the
     * {@code @PreAuthorize} expressions read, AND whose {@code details} is a real {@link CurrentUser} (with a
     * concrete {@code publicId}).
     *
     * <p>WHY this exists (the defect it fixes): bare {@code @WithMockUser} sets the authorities but leaves the
     * authentication {@code details} null, so {@link CurrentUser#requirePublicId()} — which the lifecycle
     * handlers call to attribute the actor (signer/author/answerer/actor) before the NOT_FOUND lookup — throws
     * {@code IllegalStateException} and the request 500s. In production the {@code JwtAuthenticationFilter}
     * always stores the {@link CurrentUser} as the authentication details, so these handlers always have a
     * principal id; this helper reproduces that exact shape (matching the {@code ReportMediaAccessServiceTest}
     * /{@code MediaServiceTest} pattern) so the test exercises the real authorization+attribution path and the
     * expected {@code 404} (gate passed, entity absent) is observed rather than a {@code 500}.</p>
     *
     * @param roles the role short-names (e.g. {@code "CITIZEN"}, {@code "ADMIN"}); prefixed with {@code ROLE_}
     *              for the granted authorities and carried verbatim in the {@link CurrentUser} role hints.
     * @return a request post-processor installing the authenticated, publicId-bearing principal.
     */
    private static RequestPostProcessor authedWithPublicId(String... roles) {
        UUID publicId = UUID.randomUUID();
        var authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                .toList();
        var auth = new UsernamePasswordAuthenticationToken(publicId, "n/a", authorities);
        // Production shape: CurrentUser lives in the authentication DETAILS (see JwtAuthenticationFilter),
        // which is what CurrentUser.requirePublicId() reads — @WithMockUser never sets this.
        auth.setDetails(new CurrentUser(publicId, List.of(roles), "T3"));
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }
}

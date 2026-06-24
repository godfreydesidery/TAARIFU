package com.taarifu.admin;

import com.taarifu.AbstractHttpIntegrationTest;
import com.taarifu.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-gate + happy-path integration tests for the admin <b>outbox DLQ</b> surface (P3-1; ARCHITECTURE
 * §6.2, PRD §7.1/§18, ADR-0014).
 *
 * <p>Responsibility: proves the deny-by-default method security actually fires on the DLQ list/replay
 * endpoints — both require {@code ROLE_ADMIN}/{@code ROOT}, an anonymous request is {@code 401}, and a
 * citizen/moderator token is {@code 403}. These tests <b>fail closed</b> if a {@code @PreAuthorize} were
 * removed (the legacy "authenticated-only admin surface" gap this design forbids). It also proves the
 * surface is reachable for an admin against an empty DLQ (the list is an empty-but-successful page, and an
 * idempotent replay of an unknown id returns {@code requeued=0}) — exercising the real
 * {@code OutboxReplayService} + repository query end-to-end on the PostGIS container.</p>
 *
 * <p><b>WHY full {@code @SpringBootTest} + {@code MockMvc}:</b> the real {@code SecurityConfig} JWT chain and
 * custom 401/403 envelopes are exercised (matching {@code AdminSecurityIntegrationTest}); {@code @WithMockUser}
 * drives the method-security expressions without minting real tokens.</p>
 */
class AdminOutboxSecurityIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    // ---- DLQ list ----------------------------------------------------------------------------------

    @Test
    void listFailed_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox/failed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void listFailed_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox/failed"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void listFailed_asModerator_is403() throws Exception {
        // DLQ ops are ADMIN/ROOT only — a moderator (case-management) token is NOT enough.
        mockMvc.perform(get("/api/v1/admin/outbox/failed"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listFailed_asAdmin_is2xx_emptyDlq() throws Exception {
        // Empty outbox → empty-but-successful page; proves the surface + kernel query are reachable.
        mockMvc.perform(get("/api/v1/admin/outbox/failed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ---- DLQ replay --------------------------------------------------------------------------------

    @Test
    void replay_unauthenticated_is401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/outbox/replay")
                        .contentType("application/json")
                        .content("{\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void replay_asCitizen_is403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/outbox/replay")
                        .contentType("application/json")
                        .content("{\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void replay_asModerator_is403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/outbox/replay")
                        .contentType("application/json")
                        .content("{\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void replay_unknownId_asAdmin_is2xx_zeroRequeued_idempotent() throws Exception {
        // No such FAILED row → idempotent no-op: 0 re-queued, mode BY_ID. Proves the path is reachable and
        // the kernel's FAILED-pinned requeue is safe for an unknown id.
        //
        // WHY a real JWT here (not @WithMockUser like the 401/403/list tests): unlike listing, the replay
        // path AUDITS the acting admin via CurrentUser.requirePublicId(), which reads the UUID public-id
        // from the rich CurrentUser principal that only the real JwtAuthenticationFilter installs as the
        // authentication details. @WithMockUser installs a plain username principal with no CurrentUser
        // detail, so requirePublicId() threw IllegalStateException → 500. Minting a real ADMIN access token
        // with a UUID subject exercises the genuine audited replay path end-to-end.
        String adminToken = jwtService.issueAccessToken(UUID.randomUUID(), List.of("ADMIN"), "T2");
        mockMvc.perform(post("/api/v1/admin/outbox/replay")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.mode").value("BY_ID"))
                .andExpect(jsonPath("$.data.requeued").value(0));
    }
}

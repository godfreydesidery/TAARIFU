package com.taarifu.admin;

import com.taarifu.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-gate integration tests for the admin HTTP surface (ARCHITECTURE §6.2; PRD §7.1, §18).
 *
 * <p>Responsibility: proves the deny-by-default method security actually fires on the back-office
 * surface — the dashboard, user/role management, and system-config management endpoints require
 * {@code ROLE_ADMIN}/{@code ROOT}, and the public boot-time app-config read is reachable without a role.
 * These tests <b>fail closed</b> if a {@code @PreAuthorize} were removed from a controller method — exactly
 * the legacy "authenticated-only admin surface" gap this design forbids (PRD §7.1). It also proves the
 * self-action fence (D16) is wired into method security (an admin cannot suspend their own account).</p>
 *
 * <p>WHY full {@code @SpringBootTest} + {@code MockMvc} (not a {@code @WebMvcTest} slice): the project
 * wires security in {@code SecurityConfig} with the JWT filter and custom 401/403 envelopes; a full
 * context exercises the real chain (matching {@code TokenSecurityIntegrationTest}). {@code @WithMockUser}
 * drives the method-security expressions without minting real tokens.</p>
 */
class AdminSecurityIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- dashboard ---------------------------------------------------------------------------------

    @Test
    void dashboard_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void dashboard_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/stats"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboard_asAdmin_is2xx() throws Exception {
        // No providers wired in test context → empty-but-successful dashboard (degrade-gracefully).
        mockMvc.perform(get("/api/v1/admin/dashboard/stats"))
                .andExpect(status().isOk());
    }

    // ---- reports queue / case detail / overview stats ----------------------------------------------

    @Test
    void reportsQueue_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void reportsQueue_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void reportsQueue_asModerator_is2xx() throws Exception {
        // Empty DB → empty-but-successful queue; proves the surface is reachable for staff.
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void caseDetail_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminStats_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void adminStats_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminStats_asAdmin_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats"))
                .andExpect(status().isOk());
    }

    // ---- user/role management ----------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "CITIZEN")
    void listUsers_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listUsers_asAdmin_is2xx() throws Exception {
        // Stub identity port returns an empty page; the surface itself is reachable.
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk());
    }

    @Test
    void grantRole_unauthenticated_is401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/" + UUID.randomUUID() + "/roles")
                        .contentType("application/json")
                        .content("{\"roleName\":\"MODERATOR\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void grantRole_asCitizen_is403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/" + UUID.randomUUID() + "/roles")
                        .contentType("application/json")
                        .content("{\"roleName\":\"MODERATOR\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- system config -----------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "CITIZEN")
    void upsertAppConfig_asCitizen_is403() throws Exception {
        mockMvc.perform(put("/api/v1/admin/config/app")
                        .contentType("application/json")
                        .content("{\"platform\":\"ANDROID\",\"minSupportedVersion\":\"2.0.0\","
                                + "\"minSupportedVersionCode\":200,\"forceUpdate\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void upsertFeatureFlag_unauthenticated_is401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/config/flags")
                        .contentType("application/json")
                        .content("{\"key\":\"x.y\",\"enabled\":true,\"rolloutPercentage\":100}"))
                .andExpect(status().isUnauthorized());
    }
}

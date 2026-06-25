package com.taarifu.analytics;

import com.taarifu.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-gate integration tests for the analytics dashboard surface (ARCHITECTURE.md §6.2; PRD §7.1,
 * §18; US-15.1; Appendix E.4).
 *
 * <p>Responsibility: proves the deny-by-default method security actually fires — the dashboards require
 * authentication and an Admin/authority role, and a plain citizen is forbidden. These tests <b>fail closed</b>
 * if a {@code @PreAuthorize} were removed from a handler, which is exactly the "authenticated-only admin
 * surface" gap this design forbids (PRD §7.1) and the no-PII-to-citizens posture the dashboards must keep
 * (Appendix E.4). They also confirm the endpoints are NOT in the public reference-data allow-list (a citizen
 * gets 403, an anonymous request 401 — not 200).</p>
 */
class AnalyticsSecurityIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsVolume_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/reports/volume"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verificationFunnel_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/verification/funnel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void reportsVolume_asCitizen_is403() throws Exception {
        // A plain citizen must never read the operational dashboards (Admin/authority only — US-15.1).
        mockMvc.perform(get("/api/v1/admin/analytics/reports/volume"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void slaBreaches_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/reports/sla-breaches"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reportsVolume_asAdmin_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/reports/volume"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MODERATOR")
    void moderationActions_asModerator_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/moderation/actions"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RESPONDER_ADMIN")
    void ttr_asResponderAdmin_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/reports/ttr"))
                .andExpect(status().isOk());
    }

    @Test
    void reportsTrend_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/reports/trend"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void slaBreachTrend_asCitizen_is403() throws Exception {
        // A plain citizen must never read the SLA-breach trend (Admin/authority only — US-15.1).
        mockMvc.perform(get("/api/v1/admin/analytics/reports/sla-breach-trend"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reportsTrend_asAdmin_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/reports/trend"))
                .andExpect(status().isOk());
    }

    // --- The admin-module composed overview surface (ADR-0020 §3) ---------------------------------

    @Test
    void adminOverview_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void adminOverview_asCitizen_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/overview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminOverview_asAdmin_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/overview"))
                .andExpect(status().isOk());
    }
}

package com.taarifu.tokens;

import com.taarifu.AbstractPostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-gate integration tests for the tokens HTTP surface (ARCHITECTURE.md §6.2; PRD §18, §23).
 *
 * <p>Responsibility: proves the deny-by-default method security actually fires — the wallet endpoints
 * require authentication, and the admin config endpoints require {@code ROLE_ADMIN}. These tests <b>fail
 * closed</b> if a {@code @PreAuthorize} were removed from a controller method, which is exactly the legacy
 * "authenticated-only admin surface" gap this design forbids (PRD §7.1).</p>
 *
 * <p>WHY full {@code @SpringBootTest} + {@code MockMvc} (not a {@code @WebMvcTest} slice): the project wires
 * security in {@code SecurityConfig} with a JWT filter and custom 401/403 envelopes; a full context exercises
 * that real chain, matching the repo's existing security-test approach (TierGateForgedClaimIntegrationTest).
 * {@code @WithMockUser} drives the method-security expressions without minting real tokens.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenSecurityIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getMyWallet_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/me/wallet"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyLedger_unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/me/wallet/ledger"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CITIZEN")
    void adminListPolicies_asCitizen_is403() throws Exception {
        // A plain citizen must be forbidden from the admin token-config surface (ROLE_ADMIN only).
        mockMvc.perform(get("/api/v1/admin/tokens/policies"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminListPolicies_asAdmin_is2xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tokens/policies"))
                .andExpect(status().isOk());
    }

    @Test
    void adminUpsertPolicy_unauthenticated_is401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tokens/policies")
                        .contentType("application/json")
                        .content("{\"actionCode\":\"FILE_REPORT\",\"tokenCost\":5,"
                                + "\"freeQuotaPeriod\":\"DAILY\",\"freeQuotaCount\":3}"))
                .andExpect(status().isUnauthorized());
    }
}

package com.taarifu.e2e;

import com.taarifu.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E contract smoke test for the <b>anonymous public read surface</b> on the merged platform
 * (PRD §11/§22.6 public scope; ARCHITECTURE §5.4 context-path; SecurityConfig {@code PUBLIC_GET_PATTERNS}).
 *
 * <p><b>Why this test exists (regression guard).</b> The whole API is mounted under the servlet
 * context-path {@code /api/v1} (ARCHITECTURE §5.4) and Spring Security matches the allow-list against the
 * <i>context-relative</i> path; a request that does not carry the context-path is dispatched with the wrong
 * servlet path and never matches a handler (the {@code NoResourceFoundException}/silent-401 class of bug the
 * {@link AbstractHttpIntegrationTest} base was created to fix). This test drives the four flagship public
 * reads the citizen/web/mobile clients depend on through the full security filter chain at the real
 * {@code /api/v1/...} path and asserts each returns {@code 200} with the single {@link
 * com.taarifu.common.api.dto.ApiResponse} envelope ({@code success:true}). It is the cross-module regression
 * guard that the context-path fix holds for <b>every</b> public surface, not just the one module each module
 * test exercises.</p>
 *
 * <p>It also asserts the deny-by-default counterpart: an unauthenticated call to an authenticated-only
 * endpoint ({@code GET /me/reports}) is rejected {@code 401} with the same envelope — so "public" is an
 * explicit allow, never an accidental open door (PRD §7.1 — no merely-authenticated admin/citizen surface).</p>
 *
 * <p>TEST-ONLY: this class adds no production code. It runs on the shared PostGIS Testcontainer via the
 * {@code test} profile (Docker required; CI-gated), with the schema generated from the entities — the public
 * lists return empty pages on an unseeded DB, which is exactly the {@code 200}-empty contract the clients
 * must tolerate. The point is the <b>routing + security + envelope</b> contract, not the data.</p>
 */
class PublicReadsContractE2ETest extends AbstractHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Geography reference reads ({@code /regions}) are public — the find-my-area entry point (M1). */
    @Test
    void anonymousRegions_returns200Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** The representatives directory ({@code /representatives}) is public — find-my-rep (M2). */
    @Test
    void anonymousRepresentatives_returns200Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/representatives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** Petitions public list ({@code /petitions}) is public — engagement civic graph (M9). */
    @Test
    void anonymousPetitions_returns200Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/petitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** Issue categories ({@code /issue-categories}) are public — the report-filing taxonomy (M3). */
    @Test
    void anonymousIssueCategories_returns200Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/issue-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * Deny-by-default counterpart: an unauthenticated call to an authenticated-only citizen endpoint is
     * {@code 401}, rendered as the same envelope ({@code success:false}). Proves the public allow-list is a
     * tight, explicit set — not an accidental open door (PRD §7.1, SecurityConfig {@code anyRequest().authenticated()}).
     */
    @Test
    void anonymousProtectedEndpoint_isUnauthorizedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/me/reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * Anti-enumeration / write-protection: a POST to a public-GET prefix ({@code /issue-categories}) is NOT
     * covered by the GET-only allow-list, so an anonymous write is rejected (401/403) — never silently
     * dispatched. Guards that {@code PUBLIC_GET_PATTERNS} is scoped to {@link
     * org.springframework.http.HttpMethod#GET} (SecurityConfig).
     */
    @Test
    void anonymousPostToPublicGetPrefix_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/issue-categories")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    if (sc != 401 && sc != 403) {
                        throw new AssertionError("anonymous POST to a public-GET prefix must be 401/403, was " + sc);
                    }
                });
    }
}

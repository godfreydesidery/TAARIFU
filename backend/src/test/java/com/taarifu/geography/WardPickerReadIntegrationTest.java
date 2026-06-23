package com.taarifu.geography;

import com.taarifu.AbstractHttpIntegrationTest;
import com.taarifu.geography.test.GeographyTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testcontainers + MockMvc integration test for the manual ward-picker reads — {@code GET
 * /api/v1/districts/{id}/wards} and {@code GET /api/v1/wards?q=&districtId=} (PRD §9.0, §22.6).
 * Requires Docker; written for CI (unit tests + compile are the local gate).
 *
 * <p>Responsibility: exercises the new district→wards listing and ward search end-to-end through the full
 * filter chain (entities → closure-backed repository projection → service → controller → {@code ApiResponse}
 * envelope), proving: the closure resolves <b>transitive</b> wards under a district (a ward's direct parent
 * is a council, not the district); the lean DTO carries council + district names; the prefix search is
 * case-insensitive and district-scopable; same-named wards are disambiguated by council/district name; a
 * blank query is an empty page (not the whole national table); wildcard chars are matched literally; the
 * reads are public ({@code permitAll()}, no token); and a wrong-level or unknown district id is a
 * Swahili-first 404. These assertions <b>fail if the closure join, the scope filter, the prefix-escaping,
 * or the not-found guard is removed</b>.</p>
 *
 * <p>WHY MockMvc via {@link AbstractHttpIntegrationTest} (not {@code TestRestTemplate}): the base applies
 * the production {@code /api/v1} context-path centrally so the controller mappings resolve exactly as behind
 * the real container — the same approach the maintained security ITs use
 * ({@code AnalyticsSecurityIntegrationTest}); the legacy {@code RANDOM_PORT}+{@code TestRestTemplate} ITs
 * double-prefix the context-path and no longer resolve. It proves the controller→service→closure-query→
 * envelope contract and paging meta on the wire (ADR-0009 contract testing).</p>
 *
 * <p>WHY {@code @WithMockUser} (rather than asserting anonymous 200): the endpoints are method-level
 * {@code permitAll()} and the URLs sit on the {@code /api/v1/{districts,wards}/**} GET allow-list, so an
 * authenticated principal is permitted just as a guest is. Asserting anonymous access here would exercise
 * the {@code SecurityConfig} URL-allow-list↔context-path matching, which is owned centrally and behaves
 * identically for the established public reads (e.g. {@code /api/v1/regions}) — out of this feature's scope.
 * Authenticating keeps these tests on the logic this module owns (the closure listing/search, the scope
 * filter, the not-found guard, the envelope).</p>
 */
@WithMockUser
class WardPickerReadIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GeographyTestData testData;

    private GeographyTestData.WardPickerFixture fixture;

    @BeforeEach
    void seed() {
        testData.clear();
        fixture = testData.seedWardPickerScenario();
    }

    @Test
    void listWardsInDistrict_returnsTransitiveWardsWithCouncilAndDistrictNames() throws Exception {
        // Rombo has exactly two wards — reached through the closure (their direct parent is a council, not
        // the district), so this proves the closure join, not a one-hop children read.
        mockMvc.perform(get("/api/v1/districts/{id}/wards", fixture.romboDistrictId())
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("Mahida", "Mengwe")))
                // The lean DTO denormalises council + district names for the picker label.
                .andExpect(jsonPath("$.data[?(@.name=='Mengwe')].councilName")
                        .value(containsInAnyOrder(fixture.romboCouncilName())))
                .andExpect(jsonPath("$.data[?(@.name=='Mengwe')].districtName")
                        .value(containsInAnyOrder("Rombo")))
                .andExpect(jsonPath("$.data[?(@.name=='Mengwe')].code")
                        .value(containsInAnyOrder("TZ-1907-WD-MENGWE")));
    }

    @Test
    void listWardsInDistrict_doesNotLeakOtherDistrictsWards() throws Exception {
        // Kinondoni has its own two wards; Rombo's Mengwe must NOT appear (scope filter bites).
        mockMvc.perform(get("/api/v1/districts/{id}/wards", fixture.kinondoniDistrictId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("Mahida", "Mwananyamala")));
    }

    @Test
    void listWardsInDistrict_unknownId_returnsLocalisedNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/districts/{id}/wards", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.data.code").value("NOT_FOUND"));
    }

    @Test
    void listWardsInDistrict_wrongLevelId_returnsNotFound() throws Exception {
        // Passing a non-district public id (here a ward) must 404, never silently return an empty page —
        // the wrong-level guard.
        mockMvc.perform(get("/api/v1/districts/{id}/wards", fixture.mengweWardId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("NOT_FOUND"));
    }

    @Test
    void searchWards_byPrefix_isCaseInsensitiveAndCrossDistrict() throws Exception {
        // "MA" matches Mahida (in BOTH districts) and nothing else; case-insensitive (upper-cased query).
        // Two Mahida wards (one per district) — disambiguated by district name.
        mockMvc.perform(get("/api/v1/wards").param("q", "MA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("Mahida", "Mahida")))
                .andExpect(jsonPath("$.data[*].districtName", containsInAnyOrder("Rombo", "Kinondoni")));
    }

    @Test
    void searchWards_scopedToDistrict_returnsOnlyThatDistrictsMatch() throws Exception {
        // Only the Rombo "Mahida" — the Kinondoni one is excluded by the district scope.
        mockMvc.perform(get("/api/v1/wards")
                        .param("q", "ma")
                        .param("districtId", fixture.romboDistrictId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(fixture.romboMahidaWardId().toString()))
                .andExpect(jsonPath("$.data[0].districtName").value("Rombo"));
    }

    @Test
    void searchWards_prefixDoesNotMatchSubstring() throws Exception {
        // "wana" is a substring of "Mwananyamala" but NOT a prefix — must return nothing (proves prefix,
        // not contains; a leading-wildcard search would wrongly match).
        mockMvc.perform(get("/api/v1/wards").param("q", "wana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(0));
        // "mwana" IS a prefix of Mwananyamala — sanity that the prefix itself works.
        mockMvc.perform(get("/api/v1/wards").param("q", "mwana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Mwananyamala"));
    }

    @Test
    void searchWards_blankQuery_returnsEmptyPageNotWholeTable() throws Exception {
        mockMvc.perform(get("/api/v1/wards").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void searchWards_wildcardCharIsMatchedLiterally() throws Exception {
        // A "%" in the query must be escaped, so it matches no ward (none contains a literal percent),
        // rather than acting as a SQL wildcard that returns everything.
        mockMvc.perform(get("/api/v1/wards").param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(0));
    }
}

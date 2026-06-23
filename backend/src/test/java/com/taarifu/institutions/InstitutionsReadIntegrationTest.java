package com.taarifu.institutions;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.api.dto.ApiError;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.institutions.api.dto.MyRepresentativesDto;
import com.taarifu.institutions.api.dto.PoliticalPartyDto;
import com.taarifu.institutions.test.InstitutionsTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the institutions public read slice (FOUNDATION-SCOPE.md §6,
 * ADR-0009). Requires Docker; written for CI — local runs without Docker are expected to skip/fail at
 * container start and that is acceptable per the build instructions (unit tests + compile are the local gate).
 *
 * <p>Responsibility: exercises the read endpoints end-to-end over HTTP against a real Postgres — entities
 * → repositories → service → controller → {@link ApiResponse} envelope — and verifies the envelope shape,
 * the public {@code permitAll()} access (no token), the find-my-rep fan-out (MP via Ward→Constituency +
 * ward Councillor/exec), and the localised not-found path. Schema is generated from entities under the
 * {@code test} profile (Flyway lands in the migration test), proving entities and queries agree.</p>
 *
 * <p><b>WHY context-relative paths (e.g. {@code "/parties"}, not {@code "/api/v1/parties"}).</b> The
 * application sets {@code server.servlet.context-path=/api/v1} (ARCHITECTURE §5.4). Under
 * {@code RANDOM_PORT}, Spring Boot's {@code LocalHostUriTemplateHandler} builds the
 * {@link TestRestTemplate} root URI as {@code http://localhost:<port>/api/v1} — the context-path is
 * <b>already baked into the base URI</b>. A {@code /api/v1/...} path therefore double-prefixes to
 * {@code /api/v1/api/v1/...}, matches no controller, falls through to the static-resource handler, and
 * fails. Every request below is written context-relative so the handler resolves as it does behind the
 * real container.</p>
 *
 * <p><b>WHY an explicit anonymous-read assertion (regression guard for security fix b64b108).</b>
 * {@link TestRestTemplate} sends no Authorization header, so every call here is anonymous.
 * {@link #listParties_anonymousGet_returns200_guardsPublicAllowList()} asserts that an anonymous public
 * GET returns {@code 200}, not {@code 401} — locking the {@code SecurityConfig} context-relative GET
 * allow-list (e.g. {@code /parties/**}, {@code /representatives/**}) so the silent-401 regression fixed
 * in b64b108 (allow-list patterns drifting back to context-path-prefixed forms that no longer match the
 * dispatched servlet path) can never recur.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InstitutionsReadIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InstitutionsTestData testData;

    private InstitutionsTestData.Fixture fixture;

    @BeforeEach
    void seed() {
        testData.clear();
        fixture = testData.seed();
    }

    @Test
    void listParties_returnsPagedEnvelope_publiclyWithoutToken() {
        ResponseEntity<ApiResponse<List<PoliticalPartyDto>>> response = restTemplate.exchange(
                "/parties?page=0&size=10", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<List<PoliticalPartyDto>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.statusCode()).isEqualTo(200);
        assertThat(body.meta()).isNotNull();
        assertThat(body.data()).extracting(PoliticalPartyDto::abbreviation).contains("CCM");
    }

    /**
     * Regression guard for security fix b64b108: an UNAUTHENTICATED (anonymous) GET to a public
     * institutions read must return {@code 200}, never {@code 401}.
     *
     * <p>{@link TestRestTemplate} attaches no Authorization header, so this request is genuinely
     * anonymous. The assertion locks two coupled facts the silent-401 bug broke together: (1) the
     * {@code SecurityConfig} GET allow-list is written <b>context-relative</b> ({@code /parties/**}, not
     * {@code /api/v1/parties/**}) so it matches the dispatched servlet path under the {@code /api/v1}
     * context-path; and (2) the public directory read is reachable without a token (PRD §11). If either
     * regresses — the allow-list pattern drifts back to a context-path-prefixed form, or the endpoint is
     * accidentally moved behind authentication — this fails with {@code 401}/{@code 403} instead of
     * {@code 200}.</p>
     */
    @Test
    void listParties_anonymousGet_returns200_guardsPublicAllowList() {
        ResponseEntity<ApiResponse<List<PoliticalPartyDto>>> response = restTemplate.exchange(
                "/parties?page=0&size=1", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        // The headline guard: anonymous public read is permitted (200), NOT silently rejected (401).
        assertThat(response.getStatusCode())
                .as("anonymous GET /parties must be permitted (b64b108 silent-401 regression guard)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().statusCode()).isEqualTo(200);
    }

    @Test
    void findMyRepresentatives_byWard_returnsMpCouncillorAndWardExec() {
        // Seed one SITTING MP on the constituency, a councillor + ward-exec on the ward.
        testData.insertRepresentative("MP", "CONSTITUENCY", fixture.constituencyId(), null, "SITTING");
        testData.insertRepresentative("COUNCILLOR", "COUNCILLOR_WARD", null, fixture.wardId(), "SITTING");
        testData.insertRepresentative("WARD_EXEC", "COUNCILLOR_WARD", null, fixture.wardId(), "SITTING");

        ResponseEntity<ApiResponse<MyRepresentativesDto>> response = restTemplate.exchange(
                "/representatives/by-ward/" + fixture.wardPublicId(), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MyRepresentativesDto dto = response.getBody().data();
        assertThat(dto.wardName()).isEqualTo("Mengwe");
        assertThat(dto.constituencyName()).isEqualTo("Rombo");
        assertThat(dto.mp()).isNotNull();
        assertThat(dto.mp().type()).isEqualTo("MP");
        assertThat(dto.councillors()).extracting("type").containsExactly("COUNCILLOR");
        assertThat(dto.wardExecutives()).extracting("type").containsExactly("WARD_EXEC");
    }

    @Test
    void getParty_unknownId_returnsLocalisedNotFoundEnvelope() {
        ResponseEntity<ApiResponse<ApiError>> response = restTemplate.exchange(
                "/parties/" + UUID.randomUUID(), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().statusCode()).isEqualTo(404);
        assertThat(response.getBody().data().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isNotBlank();
    }
}

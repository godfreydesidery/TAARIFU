package com.taarifu.geography;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.api.dto.ApiError;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.geography.api.dto.ConstituencyDto;
import com.taarifu.geography.api.dto.LocationResolutionDto;
import com.taarifu.geography.api.dto.RegionDto;
import com.taarifu.geography.test.GeographyTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the geography read endpoints (FOUNDATION-SCOPE.md §6, ADR-0009).
 *
 * <p>Responsibility: exercises the entire read slice end-to-end over HTTP against a real PostGIS
 * database — entities → repositories → service → controller → {@link ApiResponse} envelope — and
 * verifies the envelope shape, pagination, the not-found path, and the effective-dated ward→constituency
 * resolution that is the heart of "find my representative" (PRD §9.0).</p>
 *
 * <p>WHY HTTP (TestRestTemplate) rather than calling the service directly: it proves the envelope,
 * status codes, and method-security {@code permitAll()} actually apply on the wire — the contract a
 * client depends on (ADR-0009 contract testing).</p>
 *
 * <p><b>WHY context-relative paths (e.g. {@code "/regions"}, not {@code "/api/v1/regions"}).</b> The
 * application sets {@code server.servlet.context-path=/api/v1} (ARCHITECTURE §5.4). Under
 * {@code RANDOM_PORT}, Spring Boot's {@code LocalHostUriTemplateHandler} builds the
 * {@link TestRestTemplate} root URI as {@code http://localhost:<port>/api/v1} — the context-path is
 * <b>already baked into the base URI</b>. Passing a {@code /api/v1/...} path therefore double-prefixes
 * to {@code /api/v1/api/v1/...}, which matches no controller, falls through to the static-resource
 * handler, and fails. Every request below is written context-relative so the handler resolves exactly
 * as it does behind the real container.</p>
 *
 * <p><b>WHY an explicit anonymous-read assertion (regression guard for security fix b64b108).</b>
 * {@link TestRestTemplate} sends <b>no</b> Authorization header, so every call here is an anonymous
 * (unauthenticated) request. {@link #listRegions_anonymousGet_returns200_guardsPublicAllowList()}
 * asserts that an anonymous public GET returns {@code 200}, not {@code 401} — locking the
 * {@code SecurityConfig} context-relative GET allow-list (e.g. {@code /regions/**}) so the silent-401
 * regression fixed in b64b108 (allow-list patterns drifting back to context-path-prefixed forms that
 * no longer match the dispatched servlet path) can never recur.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GeographyReadIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private GeographyTestData testData;

    private GeographyTestData.Fixture fixture;

    @BeforeEach
    void seed() {
        testData.clear();
        fixture = testData.seedKilimanjaroRomboMengwe();
    }

    @Test
    void listRegions_returnsPagedEnvelope() {
        ResponseEntity<ApiResponse<List<RegionDto>>> response = restTemplate.exchange(
                "/regions?page=0&size=10",
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<List<RegionDto>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        // Success envelopes carry the integer HTTP status 200 at the top level (ADR-0008).
        assertThat(body.statusCode()).isEqualTo(200);
        // Envelope carries pagination meta for a paged endpoint.
        assertThat(body.meta()).isNotNull();
        assertThat(body.meta().total()).isGreaterThanOrEqualTo(1);
        assertThat(body.data()).extracting(RegionDto::name).contains("Kilimanjaro");
    }

    /**
     * Regression guard for security fix b64b108: an UNAUTHENTICATED (anonymous) GET to a public
     * reference-data endpoint must return {@code 200}, never {@code 401}.
     *
     * <p>{@link TestRestTemplate} attaches no Authorization header, so this request is genuinely
     * anonymous. The assertion locks two coupled facts that the silent-401 bug broke together: (1) the
     * {@code SecurityConfig} GET allow-list is written <b>context-relative</b> ({@code /regions/**}, not
     * {@code /api/v1/regions/**}) so it matches the dispatched servlet path under the {@code /api/v1}
     * context-path; and (2) the public read is reachable without a token. If either regresses — the
     * allow-list pattern drifts back to a context-path-prefixed form, or the endpoint is accidentally
     * moved behind authentication — this test fails with a {@code 401}/{@code 403} instead of {@code 200}.</p>
     */
    @Test
    void listRegions_anonymousGet_returns200_guardsPublicAllowList() {
        ResponseEntity<ApiResponse<List<RegionDto>>> response = restTemplate.exchange(
                "/regions?page=0&size=1",
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        // The headline guard: anonymous public read is permitted (200), NOT silently rejected (401).
        assertThat(response.getStatusCode())
                .as("anonymous GET /regions must be permitted (b64b108 silent-401 regression guard)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().statusCode()).isEqualTo(200);
    }

    @Test
    void listDistricts_returnsRegionChildren() {
        ResponseEntity<ApiResponse<List<Object>>> response = restTemplate.exchange(
                "/regions/" + fixture.regionPublicId() + "/districts",
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).hasSize(1); // Rombo
    }

    @Test
    void getLocation_unknownId_returnsLocalisedNotFoundEnvelope() {
        // Error bodies carry an ApiError in data → deserialize as ApiResponse<ApiError> so we can read
        // the top-level int statusCode and the preserved machine code at data.code (ADR-0008).
        ResponseEntity<ApiResponse<ApiError>> response = restTemplate.exchange(
                "/locations/" + java.util.UUID.randomUUID(),
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        // Top level is the integer HTTP status; the stable machine code lives at data.code.
        assertThat(response.getBody().statusCode()).isEqualTo(404);
        assertThat(response.getBody().data()).isNotNull();
        assertThat(response.getBody().data().code()).isEqualTo("NOT_FOUND");
        // Swahili-first message (default locale).
        assertThat(response.getBody().message()).isNotBlank();
    }

    @Test
    void getConstituency_includesCurrentWardsViaEffectiveDatedBridge() {
        ResponseEntity<ApiResponse<ConstituencyDto>> response = restTemplate.exchange(
                "/constituencies/" + fixture.constituencyPublicId(),
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ConstituencyDto dto = response.getBody().data();
        assertThat(dto.name()).isEqualTo("Rombo");
        // The current ward mapping (effective_to = null) resolves Mengwe into the constituency.
        assertThat(dto.wards()).extracting("name").contains("Mengwe");
    }

    @Test
    void resolve_viaStubGeocoder_returnsWardAndConstituency() {
        ResponseEntity<ApiResponse<LocationResolutionDto>> response = restTemplate.exchange(
                "/locations/resolve?lat=-3.07&lng=37.55",
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LocationResolutionDto dto = response.getBody().data();
        // Stub geocoder resolves to the seeded ward; admin chain + constituency are derived.
        assertThat(dto.resolved()).isTrue();
        assertThat(dto.ward().name()).isEqualTo("Mengwe");
        assertThat(dto.adminChain()).extracting("name").contains("Kilimanjaro", "Rombo");
        assertThat(dto.constituency()).isNotNull();
        assertThat(dto.constituency().name()).isEqualTo("Rombo");
    }
}

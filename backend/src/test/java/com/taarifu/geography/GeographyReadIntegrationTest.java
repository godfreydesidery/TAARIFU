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
                "/api/v1/regions?page=0&size=10",
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

    @Test
    void listDistricts_returnsRegionChildren() {
        ResponseEntity<ApiResponse<List<Object>>> response = restTemplate.exchange(
                "/api/v1/regions/" + fixture.regionPublicId() + "/districts",
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
                "/api/v1/locations/" + java.util.UUID.randomUUID(),
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
                "/api/v1/constituencies/" + fixture.constituencyPublicId(),
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
                "/api/v1/locations/resolve?lat=-3.07&lng=37.55",
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

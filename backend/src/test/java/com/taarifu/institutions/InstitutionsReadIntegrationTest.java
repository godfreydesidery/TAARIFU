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
                "/api/v1/parties?page=0&size=10", HttpMethod.GET, null,
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

    @Test
    void findMyRepresentatives_byWard_returnsMpCouncillorAndWardExec() {
        // Seed one SITTING MP on the constituency, a councillor + ward-exec on the ward.
        testData.insertRepresentative("MP", "CONSTITUENCY", fixture.constituencyId(), null, "SITTING");
        testData.insertRepresentative("COUNCILLOR", "COUNCILLOR_WARD", null, fixture.wardId(), "SITTING");
        testData.insertRepresentative("WARD_EXEC", "COUNCILLOR_WARD", null, fixture.wardId(), "SITTING");

        ResponseEntity<ApiResponse<MyRepresentativesDto>> response = restTemplate.exchange(
                "/api/v1/representatives/by-ward/" + fixture.wardPublicId(), HttpMethod.GET, null,
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
                "/api/v1/parties/" + UUID.randomUUID(), HttpMethod.GET, null,
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

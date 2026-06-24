package com.taarifu.e2e;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.FlywayCleanMigrateTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E smoke test for the <b>USSD aggregator webhook's shared-secret authentication</b> — the open
 * feature-phone surface (PRD §14/§18, EI-4; {@code UssdGatewaySecretFilter} P2-1, THREAT-MODEL TB-3/TR-1).
 *
 * <p><b>Why this matters.</b> {@code POST /ussd/gateway} is necessarily {@code permitAll()} at the method
 * layer — a feature-phone caller carries no JWT — so the only thing standing between the public internet and
 * the no-OTP T1 account-creation + report-filing surface is the shared-secret header verified by the
 * {@code UssdGatewaySecretFilter}. Joseph in rural Singida on a feature phone is a first-class user, but the
 * aggregator link must be authenticated. This test drives the real filter chain end-to-end and proves the
 * three states that decide whether that door is open or closed:</p>
 * <ol>
 *   <li><b>valid secret</b> → the request reaches the menu machine and returns a {@code CON } continuation
 *       (the first language screen) — the aggregator wire string, NOT the JSON envelope (ARCHITECTURE §5.1);</li>
 *   <li><b>missing secret header</b> → {@code 403}, fail-closed, before any work;</li>
 *   <li><b>wrong secret</b> → {@code 403}, constant-time rejection.</li>
 * </ol>
 *
 * <p><b>WHY a real port ({@code RANDOM_PORT} + {@link TestRestTemplate}), not MockMvc.</b> The
 * {@code UssdGatewaySecretFilter} is scoped by {@code request.getServletPath().equals("/ussd/gateway")}
 * (it must touch <i>only</i> the webhook). Behind the real servlet container the {@code /api/v1}
 * context-path is stripped, so the servlet path is exactly {@code /ussd/gateway} and the filter engages.
 * MockMvc, however, does <b>not</b> derive the servlet path from the context-path: its
 * {@code MockHttpServletRequest} reports the full {@code /api/v1/ussd/gateway} (or empty) servlet path, so
 * the filter's {@code shouldNotFilter} short-circuits and the guard never runs — the webhook then answers
 * <i>every</i> call (including a missing/wrong secret) as if authenticated. That is a test-harness fidelity
 * gap, not a production defect: the filter is correct against the real container. Running this security
 * contract over a real port reproduces the production servlet path faithfully and is the only way to assert
 * the fail-closed behaviour. (The Swahili-first {@code CON}/{@code END} wire forms are still asserted as the
 * plain-text body, never the JSON envelope.)</p>
 *
 * <p>The shared secret is provisioned here via {@link TestPropertySource} (a dummy test value, never a real
 * secret) so the webhook is <b>configured</b> for this suite — the default {@code test} profile leaves it
 * unset, i.e. fail-closed. TEST-ONLY: no production code is added.</p>
 *
 * <p><b>WHY Flyway-owned schema + {@code ddl-auto=validate}</b> (the test-harness fix): a fresh USSD
 * dialogue auto-creates the caller's T1 account on first contact ({@code linkOrCreateByMsisdn} → US-0.1),
 * which grants the {@code CITIZEN} role from the role catalogue seeded by Flyway (V102). Under the shared
 * profile's create-drop the catalogue is empty (no seed runs, the dev-admin seeder is dev-only), so the
 * valid-secret path would have failed with {@code INTERNAL_ERROR} after the secret check. Booting the real
 * migrated schema (the production configuration) supplies the seed and lets the menu machine reach the
 * first {@code CON} screen. No production behaviour is changed.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FlywayCleanMigrateTestConfig.class)   // clean-then-migrate so create-drop leftovers in the shared container never block Flyway
@TestPropertySource(properties = {
        // Dummy aggregator secret for THIS suite only (never a real secret) so the webhook is enabled.
        "taarifu.ussd.gateway.secret=e2e-ussd-shared-secret",
        "taarifu.ussd.gateway.header=X-Ussd-Secret",
        // Production schema path: Flyway owns the schema + seeds the role catalogue (V102) the first-contact
        // T1 account-creation needs; Hibernate only validates the entities against it.
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        // Allow Flyway.clean() in tests ONLY (production keeps clean disabled) so this Flyway test starts
        // from an empty schema regardless of create-drop residue in the shared container.
        "spring.flyway.clean-disabled=false"
})
class UssdGatewaySecretAuthE2ETest extends AbstractPostgisIntegrationTest {

    private static final String SECRET_HEADER = "X-Ussd-Secret";
    private static final String VALID_SECRET = "e2e-ussd-shared-secret";

    /** A minimal valid aggregator payload: a fresh dialogue (blank text → first screen). */
    private static final String FRESH_DIALOGUE_BODY = """
            {"sessionId":"sess-e2e-1","msisdn":"+255700000900","serviceCode":"*149#","text":""}
            """;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Builds the request entity for the webhook. Under {@code RANDOM_PORT} the {@link TestRestTemplate}
     * base URI already carries the {@code /api/v1} context-path, so the call is context-relative
     * ({@code /ussd/gateway}) — exactly the path the production servlet maps and the secret filter scopes to.
     *
     * @param secret the value to present in the secret header, or {@code null} to omit the header entirely.
     * @return the JSON request entity with (optionally) the secret header set.
     */
    private HttpEntity<String> request(String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) {
            headers.set(SECRET_HEADER, secret);
        }
        return new HttpEntity<>(FRESH_DIALOGUE_BODY, headers);
    }

    /**
     * Valid secret → the aggregator is authenticated, the menu machine runs, and the first screen is a
     * {@code CON } continuation (the Swahili-first language prompt). Asserts the plain-text {@code CON } wire
     * form, not a JSON envelope.
     */
    @Test
    void validSecret_returnsConMenu() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/ussd/gateway", HttpMethod.POST, request(VALID_SECRET), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).startsWith("CON ");
    }

    /**
     * Missing secret header → {@code 403}, fail-closed. The rejection is the plain-text {@code END } wire
     * form (the channel is text/plain even on rejection), never the JSON envelope.
     */
    @Test
    void missingSecret_isForbidden() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/ussd/gateway", HttpMethod.POST, request(null), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).startsWith("END ");
    }

    /**
     * Wrong secret → {@code 403}, constant-time rejection. Proves the filter does not fail-open on a
     * present-but-incorrect header.
     */
    @Test
    void wrongSecret_isForbidden() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/ussd/gateway", HttpMethod.POST, request("not-the-secret"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).startsWith("END ");
    }
}

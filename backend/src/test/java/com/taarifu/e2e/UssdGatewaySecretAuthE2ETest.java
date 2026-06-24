package com.taarifu.e2e;

import com.taarifu.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * <p>The shared secret is provisioned here via {@link TestPropertySource} (a dummy test value, never a real
 * secret) so the webhook is <b>configured</b> for this suite — the default {@code test} profile leaves it
 * unset, i.e. fail-closed. TEST-ONLY: no production code is added.</p>
 */
@TestPropertySource(properties = {
        // Dummy aggregator secret for THIS suite only (never a real secret) so the webhook is enabled.
        "taarifu.ussd.gateway.secret=e2e-ussd-shared-secret",
        "taarifu.ussd.gateway.header=X-Ussd-Secret"
})
class UssdGatewaySecretAuthE2ETest extends AbstractHttpIntegrationTest {

    private static final String SECRET_HEADER = "X-Ussd-Secret";
    private static final String VALID_SECRET = "e2e-ussd-shared-secret";

    /** A minimal valid aggregator payload: a fresh dialogue (blank text → first screen). */
    private static final String FRESH_DIALOGUE_BODY = """
            {"sessionId":"sess-e2e-1","msisdn":"+255700000900","serviceCode":"*149#","text":""}
            """;

    @Autowired
    private MockMvc mockMvc;

    /**
     * Valid secret → the aggregator is authenticated, the menu machine runs, and the first screen is a
     * {@code CON } continuation (the Swahili-first language prompt). Asserts the plain-text {@code CON } wire
     * form, not a JSON envelope.
     */
    @Test
    void validSecret_returnsConMenu() throws Exception {
        mockMvc.perform(post("/api/v1/ussd/gateway")
                        .header(SECRET_HEADER, VALID_SECRET)
                        .contentType("application/json")
                        .content(FRESH_DIALOGUE_BODY))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith("CON ")));
    }

    /**
     * Missing secret header → {@code 403}, fail-closed. The rejection is the plain-text {@code END } wire
     * form (the channel is text/plain even on rejection), never the JSON envelope.
     */
    @Test
    void missingSecret_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/ussd/gateway")
                        .contentType("application/json")
                        .content(FRESH_DIALOGUE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(content().string(startsWith("END ")));
    }

    /**
     * Wrong secret → {@code 403}, constant-time rejection. Proves the filter does not fail-open on a
     * present-but-incorrect header.
     */
    @Test
    void wrongSecret_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/ussd/gateway")
                        .header(SECRET_HEADER, "not-the-secret")
                        .contentType("application/json")
                        .content(FRESH_DIALOGUE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(content().string(startsWith("END ")));
    }
}

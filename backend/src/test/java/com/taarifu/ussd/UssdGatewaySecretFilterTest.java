package com.taarifu.ussd;

import com.taarifu.ussd.infrastructure.config.UssdGatewayProperties;
import com.taarifu.ussd.infrastructure.security.UssdGatewaySecretFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UssdGatewaySecretFilter} — aggregator shared-secret authentication on the open
 * USSD webhook (wave2-review P2-1, THREAT-MODEL TB-3/TR-1).
 *
 * <p>Responsibility: proves the guard admits a request bearing the correct secret, rejects a missing or
 * wrong secret with a plain-text {@code END} 403, <b>fails closed</b> when no secret is configured, and is
 * <b>scoped strictly</b> to {@code POST /ussd/gateway} (every other path/method passes through untouched).
 * No Spring context, no Docker — the filter is exercised directly with Spring's servlet mocks.</p>
 */
class UssdGatewaySecretFilterTest {

    private static final String SECRET = "s3cret-shared-with-aggregator-value";
    private static final String HEADER = "X-Ussd-Secret";

    private UssdGatewaySecretFilter filter(String configuredSecret) {
        return new UssdGatewaySecretFilter(new UssdGatewayProperties(configuredSecret, HEADER));
    }

    /** Builds a POST /ussd/gateway request with the given (optional) secret header. */
    private MockHttpServletRequest gatewayPost(String secretHeaderValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ussd/gateway");
        request.setServletPath("/ussd/gateway");
        if (secretHeaderValue != null) {
            request.addHeader(HEADER, secretHeaderValue);
        }
        return request;
    }

    @Test
    void correctSecret_passesThroughToTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(SECRET).doFilter(gatewayPost(SECRET), response, chain);

        // Chain advanced (the controller would run); no rejection written.
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void missingSecret_isRejectedWithPlainTextEnd() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(SECRET).doFilter(gatewayPost(null), response, chain);

        assertThat(chain.getRequest()).as("chain must NOT advance").isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString()).startsWith("END ");
        // The rejection body must never echo the secret.
        assertThat(response.getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void wrongSecret_isRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(SECRET).doFilter(gatewayPost("not-the-secret"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).startsWith("END ");
    }

    @Test
    void unconfiguredSecret_failsClosed_evenWithAHeaderPresent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // No secret configured → webhook disabled → reject regardless of what the caller presents.
        filter(null).doFilter(gatewayPost("anything"), response, chain);

        assertThat(chain.getRequest()).as("an unprovisioned webhook must be closed, not open").isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void nonGatewayPath_passesThroughUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/regions");
        other.setServletPath("/regions");
        filter(SECRET).doFilter(other, response, chain);

        // The guard must not interfere with the rest of the API: chain advances, no rejection.
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void gatewayWithWrongMethod_passesThroughUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/ussd/gateway");
        get.setServletPath("/ussd/gateway");
        filter(SECRET).doFilter(get, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}

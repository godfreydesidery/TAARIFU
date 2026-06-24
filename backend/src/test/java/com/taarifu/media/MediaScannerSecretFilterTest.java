package com.taarifu.media;

import com.taarifu.media.infrastructure.config.MediaScannerProperties;
import com.taarifu.media.infrastructure.security.MediaScannerSecretFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MediaScannerSecretFilter} — scanner service-principal shared-secret authentication
 * on the media scan-callback (MF-3, wave4-review §2).
 *
 * <p>Responsibility: proves the guard admits a callback bearing the correct secret, rejects a missing or
 * wrong secret with a 403 JSON envelope, <b>fails closed</b> when no secret is configured, and is
 * <b>scoped strictly</b> to {@code POST /media/{mediaId}/scan-callback} (every other path/method passes
 * through untouched, including the other media endpoints). No Spring context, no Docker — the filter is
 * exercised directly with Spring's servlet mocks.</p>
 */
class MediaScannerSecretFilterTest {

    private static final String SECRET = "scanner-shared-secret-value-xyz";
    private static final String HEADER = "X-Scanner-Secret";
    private static final String CALLBACK_PATH = "/media/1f2e3d4c-0000-0000-0000-000000000000/scan-callback";

    private MediaScannerSecretFilter filter(String configuredSecret) {
        return new MediaScannerSecretFilter(new MediaScannerProperties(configuredSecret, HEADER));
    }

    /** Builds a POST scan-callback request with the given (optional) secret header. */
    private MockHttpServletRequest callbackPost(String secretHeaderValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CALLBACK_PATH);
        request.setServletPath(CALLBACK_PATH);
        if (secretHeaderValue != null) {
            request.addHeader(HEADER, secretHeaderValue);
        }
        return request;
    }

    @Test
    void correctSecret_passesThroughToTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(SECRET).doFilter(callbackPost(SECRET), response, chain);

        // Chain advanced (the controller would run); no rejection written.
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void missingSecret_isRejectedWithForbiddenJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(SECRET).doFilter(callbackPost(null), response, chain);

        assertThat(chain.getRequest()).as("chain must NOT advance").isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
        // The rejection body must never echo the secret.
        assertThat(response.getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void wrongSecret_isRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(SECRET).doFilter(callbackPost("not-the-secret"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void unconfiguredSecret_failsClosed_evenWithAHeaderPresent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // No secret configured → callback disabled → reject regardless of what the caller presents.
        filter(null).doFilter(callbackPost("anything"), response, chain);

        assertThat(chain.getRequest()).as("an unprovisioned scanner link must be closed, not open").isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void otherMediaEndpoint_passesThroughUntouched() throws Exception {
        // A download request (GET /media/{id}) is NOT the scan-callback: the secret filter must not touch it,
        // so its own JWT + visibility gating applies instead.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletRequest download = new MockHttpServletRequest("GET",
                "/media/1f2e3d4c-0000-0000-0000-000000000000");
        download.setServletPath("/media/1f2e3d4c-0000-0000-0000-000000000000");
        filter(SECRET).doFilter(download, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void scanCallbackWithWrongMethod_passesThroughUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletRequest get = new MockHttpServletRequest("GET", CALLBACK_PATH);
        get.setServletPath(CALLBACK_PATH);
        filter(SECRET).doFilter(get, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void nonMediaPath_passesThroughUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/reports");
        other.setServletPath("/reports");
        filter(SECRET).doFilter(other, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}

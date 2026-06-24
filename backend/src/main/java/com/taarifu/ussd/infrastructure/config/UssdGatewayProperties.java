package com.taarifu.ussd.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised settings for authenticating the inbound USSD aggregator webhook, bound from
 * {@code taarifu.ussd.gateway.*} (wave2-review P2-1, THREAT-MODEL TB-3/TR-1, PRD §18).
 *
 * <p>Responsibility: holds the <b>shared secret</b> the aggregator must present on every
 * {@code POST /ussd/gateway} call, and the HTTP header it is presented in. The webhook is necessarily
 * {@code permitAll()} at the security layer (a feature-phone caller has no JWT — there is no user token to
 * authenticate), so the aggregator link is instead authenticated server-to-server by this shared secret
 * (a constant-time header compare in {@code UssdGatewaySecretFilter}). Without it the open webhook could be
 * driven by any internet caller to mass-provision no-OTP T1 accounts and spam reports.</p>
 *
 * <p><b>WHY no secret lives in source</b> (CLAUDE.md §12, PRD §18): the value comes <b>only</b> from the
 * environment / secret manager via the {@code application.yml} {@code ${TAARIFU_USSD_GATEWAY_SECRET}}
 * placeholder — never committed. When the secret is <b>unset</b> the webhook <b>fails closed</b> (rejects
 * every call) rather than fail-open: an un-provisioned webhook is a disabled webhook, not an open one
 * (matching the review's "keep the USSD file path disabled in prod until aggregator auth lands"). A
 * future HMAC-over-the-request scheme can replace the shared secret behind this same property surface.</p>
 *
 * @param secret the shared secret the aggregator must present; env-provided, never committed. Blank/absent
 *               → the webhook is closed (fail-safe).
 * @param header the HTTP header name the {@code secret} is presented in (default {@code X-Ussd-Secret}),
 *               so the same guard fits whatever header the procured aggregator uses.
 */
@ConfigurationProperties(prefix = "taarifu.ussd.gateway")
public record UssdGatewayProperties(
        String secret,
        String header
) {

    /** Default header the aggregator presents the shared secret in. */
    private static final String DEFAULT_HEADER = "X-Ussd-Secret";

    /** Normalises blank placeholders to {@code null} and applies the default header name. */
    public UssdGatewayProperties {
        secret = blankToNull(secret);
        header = blankToNull(header);
        if (header == null) {
            header = DEFAULT_HEADER;
        }
    }

    /**
     * @return {@code true} if a non-blank shared secret is configured. When {@code false} the webhook
     *         is closed: with no secret to verify there is no authenticated aggregator, so admitting a
     *         call would be fail-open — the guard rejects instead.
     */
    public boolean isConfigured() {
        return secret != null;
    }

    /** @return {@code null} for a {@code null}/blank string, else the trimmed value. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

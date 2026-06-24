package com.taarifu.ussd.infrastructure.security;

import com.taarifu.ussd.infrastructure.config.UssdGatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates the inbound USSD aggregator webhook by a shared secret header, scoped strictly to
 * {@code POST /ussd/gateway} (wave2-review P2-1, THREAT-MODEL TB-3/TR-1, PRD §18).
 *
 * <p>Responsibility: the server-to-server authentication the open webhook needs. The endpoint is
 * necessarily {@code permitAll()} at the method layer — a feature-phone caller carries no JWT, so there is
 * no user token to authenticate (and this module must not edit the kernel {@code SecurityConfig}; the
 * central public-allow-list registration of the path is a CENTRAL NEED). This filter closes the resulting
 * gap: it verifies the configured shared secret on the request's secret header and rejects any call that
 * is missing or wrong, <b>before</b> the controller runs or the body is parsed. Without it the no-OTP T1
 * account-creation + report-filing surface is drivable by any internet caller.</p>
 *
 * <p><b>Fail-closed</b> (PRD §18, CLAUDE.md §3 "secure by default"): when no secret is configured the
 * webhook is treated as <b>disabled</b> and every call is rejected — an un-provisioned aggregator link is
 * a closed one, never fail-open. The comparison is <b>constant-time</b> ({@link MessageDigest#isEqual}
 * over SHA-256 digests) so a timing side-channel cannot leak the secret length/prefix.</p>
 *
 * <p><b>No PII / no secret in logs</b> (S-4): a rejection logs only the outcome + reason code, never the
 * presented or expected secret, never the request body (which carries the MSISDN). The response is the
 * plain-text {@code END} the aggregator renders (this channel is text/plain, never the JSON envelope —
 * ARCHITECTURE §5.1), so a rejected handset sees a clean "service unavailable" line, not a stack trace.</p>
 *
 * <p>WHY a filter (not a controller guard): the secret check needs no request body, so doing it in the
 * filter chain rejects abusive/unauthenticated calls earliest (before body binding and the
 * {@code @Transactional} machine) and keeps the controller thin (CLAUDE.md §8). Per-MSISDN rate-limiting
 * — which <i>does</i> need the parsed MSISDN — is applied separately in the controller.</p>
 */
public class UssdGatewaySecretFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UssdGatewaySecretFilter.class);

    /** Context-relative path of the webhook (after the {@code /api/v1} context-path is stripped). */
    private static final String GATEWAY_PATH = "/ussd/gateway";

    private final UssdGatewayProperties properties;

    /**
     * @param properties the externalised aggregator secret + header settings.
     */
    public UssdGatewaySecretFilter(UssdGatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * Applies the filter only to {@code POST /ussd/gateway}; every other request is left untouched so this
     * guard cannot interfere with the rest of the API.
     *
     * @param request the inbound request.
     * @return {@code true} to skip this filter for the request.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && GATEWAY_PATH.equals(request.getServletPath()));
    }

    /**
     * Verifies the shared secret header; on success continues the chain, otherwise writes a plain-text
     * {@code END} rejection and stops.
     *
     * @param request     the inbound webhook request.
     * @param response    the response to write a rejection into on failure.
     * @param filterChain the remaining chain (continued only when the secret verifies).
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isConfigured()) {
            // Fail-closed: no secret provisioned → the webhook is disabled, not open.
            reject(response, "NOT_CONFIGURED");
            return;
        }
        String presented = request.getHeader(properties.header());
        if (presented == null || !constantTimeEquals(presented, properties.secret())) {
            reject(response, presented == null ? "MISSING_SECRET" : "BAD_SECRET");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Compares two secrets in constant time (over their SHA-256 digests, so equal-length digests defeat a
     * length/prefix timing oracle even for unequal-length inputs).
     *
     * @param presented the secret on the request.
     * @param expected  the configured secret.
     * @return {@code true} iff the two are byte-for-byte equal.
     */
    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(sha256(presented), sha256(expected));
    }

    /** @return the SHA-256 digest of {@code s} (UTF-8). */
    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS on every JVM; this is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Writes a plain-text {@code END} rejection (USSD wire form, never the JSON envelope) and a 403.
     * Logs only the reason code — never the secret or the request body (S-4, PDPA).
     *
     * @param response the response to write into.
     * @param reason   a non-PII reason code for the security log.
     */
    private void reject(HttpServletResponse response, String reason) throws IOException {
        log.warn("USSD gateway request rejected: aggregator auth failed (reason={})", reason);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
        // Swahili-first, GSM-7-safe final line; carries no detail an attacker could probe.
        response.getWriter().write("END Samahani, huduma haipatikani.");
    }
}

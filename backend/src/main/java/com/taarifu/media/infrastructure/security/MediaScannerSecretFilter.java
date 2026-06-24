package com.taarifu.media.infrastructure.security;

import com.taarifu.media.infrastructure.config.MediaScannerProperties;
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
 * Authenticates the inbound malware-scanner verdict callback by a scanner service-principal shared secret,
 * scoped strictly to {@code POST /media/{mediaId}/scan-callback} (MF-3, wave4-review §2; PRD §18, §21 EI-8).
 *
 * <p>Responsibility: the server-to-server authentication the scan-callback needs. The callback mutates an
 * object's servability — a {@code CLEAN} verdict makes evidence downloadable — so it must be reachable only
 * by the scanning pipeline, never by an arbitrary citizen token. Before MF-3 the endpoint was only
 * {@code isAuthenticated()}, so <b>any</b> authenticated citizen could POST a forged {@code CLEAN}/
 * {@code INFECTED} verdict for any object. This filter closes that gap: it verifies the configured scanner
 * secret on the request header and rejects any call that is missing or wrong, <b>before</b> the controller
 * runs or the body is parsed. It is defence-in-depth alongside (not instead of) the method-level
 * {@code @PreAuthorize} — the endpoint stays out of the public allow-list, so a real scanner presents both a
 * service-account token and this secret; the secret is the decisive factor a citizen token cannot supply.</p>
 *
 * <p><b>Fail-closed</b> (PRD §18, CLAUDE.md §3 "secure by default"): when no secret is configured the
 * callback is treated as <b>disabled</b> and every call is rejected — an un-provisioned scanner link is a
 * closed one, never fail-open. The comparison is <b>constant-time</b> ({@link MessageDigest#isEqual} over
 * SHA-256 digests) so a timing side-channel cannot leak the secret length/prefix.</p>
 *
 * <p><b>No PII / no secret in logs</b> (S-4): a rejection logs only the outcome + a non-PII reason code,
 * never the presented or expected secret and never the request body. The response is a compact JSON error
 * body carrying just the {@code FORBIDDEN} machine code (envelope-shaped — ADR-0008), with no detail an
 * attacker could probe.</p>
 *
 * <p>WHY a filter (not a controller guard): the secret check needs no request body, so doing it in the
 * filter chain rejects unauthorised calls earliest (before body binding and the {@code @Transactional}
 * machine) and keeps the controller thin (CLAUDE.md §8). It mirrors the proven {@code UssdGatewaySecretFilter}
 * pattern. This module must not edit the kernel {@code SecurityConfig}; the filter self-scopes by path so the
 * broad servlet mapping is harmless for every other request.</p>
 */
public class MediaScannerSecretFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MediaScannerSecretFilter.class);

    /** Context-relative path prefix of the media surface (after the {@code /api/v1} context-path is stripped). */
    private static final String MEDIA_PREFIX = "/media/";

    /** Context-relative path suffix identifying the scan-callback action ({@code /media/{id}/scan-callback}). */
    private static final String SCAN_CALLBACK_SUFFIX = "/scan-callback";

    private final MediaScannerProperties properties;

    /**
     * @param properties the externalised scanner secret + header settings.
     */
    public MediaScannerSecretFilter(MediaScannerProperties properties) {
        this.properties = properties;
    }

    /**
     * Applies the filter only to {@code POST /media/{mediaId}/scan-callback}; every other request is left
     * untouched so this guard cannot interfere with the rest of the API. The {@code mediaId} is a variable
     * path segment, so the match is by prefix + suffix on the servlet path.
     *
     * @param request the inbound request.
     * @return {@code true} to skip this filter for the request.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return path == null
                || !(path.startsWith(MEDIA_PREFIX) && path.endsWith(SCAN_CALLBACK_SUFFIX));
    }

    /**
     * Verifies the scanner shared-secret header; on success continues the chain, otherwise writes a JSON
     * {@code FORBIDDEN} rejection and stops.
     *
     * @param request     the inbound scan-callback request.
     * @param response    the response to write a rejection into on failure.
     * @param filterChain the remaining chain (continued only when the secret verifies).
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isConfigured()) {
            // Fail-closed: no scanner secret provisioned → the callback is disabled, not open.
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
     * Writes a compact envelope-shaped JSON {@code FORBIDDEN} rejection and a 403. Logs only the reason
     * code — never the secret or the request body (S-4, PDPA).
     *
     * @param response the response to write into.
     * @param reason   a non-PII reason code for the security log.
     */
    private void reject(HttpServletResponse response, String reason) throws IOException {
        log.warn("Media scan-callback rejected: scanner auth failed (reason={})", reason);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        // Envelope-shaped, no field-level detail; carries just the machine code (ADR-0008) — nothing an
        // attacker can probe and no secret echoed back.
        response.getWriter().write("{\"success\":false,\"data\":{\"code\":\"FORBIDDEN\"}}");
    }
}

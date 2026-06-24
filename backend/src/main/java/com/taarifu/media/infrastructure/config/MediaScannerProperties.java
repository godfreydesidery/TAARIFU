package com.taarifu.media.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised settings authenticating the inbound malware-scanner verdict callback
 * ({@code POST /media/{mediaId}/scan-callback}), bound from {@code taarifu.media.scan-callback.*}
 * (MF-3, wave4-review §2; PRD §18, §21 EI-8).
 *
 * <p>Responsibility: holds the <b>scanner service-principal shared secret</b> the scanning pipeline must
 * present on every scan-callback, and the header it is presented in. The callback mutates an object's
 * servability (a {@code CLEAN} verdict makes evidence downloadable), so it must be reachable only by the
 * scanner — not by an arbitrary citizen token. {@code MediaScannerSecretFilter} verifies this secret with a
 * constant-time compare and rejects any call that is missing/wrong, so a citizen cannot forge a
 * {@code CLEAN}/{@code INFECTED} verdict for any object.</p>
 *
 * <p><b>WHY a distinct property prefix</b> ({@code taarifu.media.scan-callback}, not
 * {@code taarifu.media.scanner}): {@code taarifu.media.scanner} is already the scalar adapter-selector key
 * for the {@code MalwareScanner} bean ({@code =stub}); reusing it would collide a leaf value with an object
 * node. The env var is {@code TAARIFU_MEDIA_SCANNER_SECRET} (the operator-facing name MF-3 asks for).</p>
 *
 * <p><b>WHY no secret lives in source</b> (CLAUDE.md §12, PRD §18): the value comes <b>only</b> from the
 * environment / secret manager via the {@code application.yml} {@code ${TAARIFU_MEDIA_SCANNER_SECRET}}
 * placeholder — never committed. When the secret is <b>unset</b> the callback <b>fails closed</b> (rejects
 * every call) rather than fail-open: an un-provisioned scanner link is a disabled one, not an open one. A
 * future HMAC-over-the-request scheme can replace the shared secret behind this same property surface.</p>
 *
 * @param secret the shared secret the scanner must present; env-provided, never committed. Blank/absent →
 *               the callback is closed (fail-safe).
 * @param header the HTTP header the {@code secret} is presented in (default {@code X-Scanner-Secret}), so
 *               the same guard fits whatever header the procured scanner uses.
 */
@ConfigurationProperties(prefix = "taarifu.media.scan-callback")
public record MediaScannerProperties(
        String secret,
        String header
) {

    /** Default header the scanner presents the shared secret in. */
    private static final String DEFAULT_HEADER = "X-Scanner-Secret";

    /** Normalises blank placeholders to {@code null} and applies the default header name. */
    public MediaScannerProperties {
        secret = blankToNull(secret);
        header = blankToNull(header);
        if (header == null) {
            header = DEFAULT_HEADER;
        }
    }

    /**
     * @return {@code true} if a non-blank shared secret is configured. When {@code false} the callback is
     *         closed: with no secret to verify there is no authenticated scanner, so admitting a call would
     *         be fail-open — the guard rejects instead.
     */
    public boolean isConfigured() {
        return secret != null;
    }

    /** @return {@code null} for a {@code null}/blank string, else the trimmed value. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

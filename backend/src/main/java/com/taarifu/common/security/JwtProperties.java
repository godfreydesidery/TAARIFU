package com.taarifu.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Externalised JWT configuration bound from {@code taarifu.security.jwt.*} (ADR-0007/0011, CLAUDE.md §12).
 *
 * <p>Responsibility: holds the signing secret, lifetimes, issuer, and audience. <b>No secret is ever
 * hard-coded</b> — the value comes from an environment variable / secret manager via
 * {@code application.yml} placeholders (PRD §18). The scaffold uses an HMAC secret; the production
 * design moves to asymmetric RS256/ES256 keys (ADR-0011 §10), changing only the {@link JwtService}
 * signer, not this contract.</p>
 *
 * <p>JWT hardening (MF-1, ADR-0011 §10): the compact constructor <b>fails fast</b> if the secret is
 * absent or shorter than 256 bits of entropy — booting with a weak/empty secret would silently mint
 * forgeable tokens (a T0→ROOT escalation path). The error message <b>never</b> contains the secret.</p>
 *
 * @param secret      HMAC signing secret (≥256 bits) injected from the environment; never committed.
 * @param issuer      the {@code iss} claim value identifying this backend; validated on every verify.
 * @param audience    the {@code aud} claim value identifying the intended consumer; validated on verify.
 * @param accessTtl   access-token lifetime (~15 min).
 * @param refreshTtl  refresh-token lifetime (~30 days).
 * @param clockSkew   tolerated clock skew on expiry/not-before checks (~30s).
 */
@ConfigurationProperties(prefix = "taarifu.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        String audience,
        Duration accessTtl,
        Duration refreshTtl,
        Duration clockSkew
) {

    /** Minimum secret strength: 256 bits = 32 bytes of UTF-8 (HMAC-SHA-256 key size). */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * Applies safe defaults and the MF-1 fail-fast secret-strength guard.
     *
     * @throws IllegalStateException if the signing secret is absent or {@code < 256 bits}. The message
     *                               is secret-free; only the (in)sufficiency is reported (PRD §18).
     */
    public JwtProperties {
        if (accessTtl == null) {
            accessTtl = Duration.ofMinutes(15);
        }
        if (refreshTtl == null) {
            refreshTtl = Duration.ofDays(30);
        }
        if (clockSkew == null) {
            clockSkew = Duration.ofSeconds(30);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "taarifu";
        }
        if (audience == null || audience.isBlank()) {
            audience = "taarifu-clients";
        }
        // MF-1: reject the empty default / a weak secret at startup rather than booting forgeable.
        int secretBytes = secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "taarifu.security.jwt.secret is absent or weaker than the required 256-bit minimum "
                    + "(need >= " + MIN_SECRET_BYTES + " bytes, got " + secretBytes + "). "
                    + "Set TAARIFU_JWT_SECRET to a strong value from the environment/secret manager.");
        }
    }
}

package com.taarifu.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalised JWT configuration bound from {@code taarifu.security.jwt.*} (ADR-0007, CLAUDE.md §12).
 *
 * <p>Responsibility: holds the signing secret and token lifetimes. <b>No secret is ever hard-coded</b>
 * — the value comes from an environment variable / secret manager via {@code application.yml}
 * placeholders (PRD §18). The scaffold uses an HMAC secret for simplicity; the production design
 * moves to asymmetric RS256/ES256 keys for rotation (ADR-0007), changing only the {@link JwtService}
 * signer, not this contract.</p>
 *
 * @param secret           HMAC signing secret (≥256 bits) injected from the environment; never committed.
 * @param issuer           the {@code iss} claim value identifying this backend.
 * @param accessTtl        access-token lifetime (~15 min).
 * @param refreshTtl       refresh-token lifetime (~30 days).
 */
@ConfigurationProperties(prefix = "taarifu.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTtl,
        Duration refreshTtl
) {

    /** Fallback lifetimes if a profile omits them — kept short/safe (ADR-0007). */
    public JwtProperties {
        if (accessTtl == null) {
            accessTtl = Duration.ofMinutes(15);
        }
        if (refreshTtl == null) {
            refreshTtl = Duration.ofDays(30);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "taarifu";
        }
    }
}

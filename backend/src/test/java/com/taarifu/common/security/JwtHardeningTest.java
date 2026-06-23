package com.taarifu.common.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JWT hardening — MF-1 (AUTH-DESIGN §10, ADR-0011 §7).
 *
 * <p>Responsibility: proves (a) the secret-strength <b>fail-fast</b> rejects an absent/weak secret at
 * construction, and (b) {@link JwtService#verify} rejects a token whose {@code iss}/{@code aud} do not
 * match this backend's configuration. No Spring context / Docker needed.</p>
 */
class JwtHardeningTest {

    private static final String STRONG_SECRET = "this-is-a-strong-test-secret-of-at-least-32-bytes!!";

    private JwtProperties props(String issuer, String audience) {
        return new JwtProperties(STRONG_SECRET, issuer, audience,
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(30));
    }

    @Test
    void absentSecret_failsFastAtStartup() {
        assertThatThrownBy(() -> new JwtProperties(null, "taarifu", "aud",
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void weakSecret_failsFastAtStartup() {
        assertThatThrownBy(() -> new JwtProperties("short", "taarifu", "aud",
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void strongSecret_constructsAndRoundTrips() {
        JwtService service = new JwtService(props("taarifu", "taarifu-clients"));
        UUID subject = UUID.randomUUID();
        String token = service.issueAccessToken(subject, java.util.List.of("CITIZEN"), "T1");

        JwtService.ParsedToken parsed = service.verify(token, TokenType.ACCESS);
        assertThat(parsed.subject()).isEqualTo(subject);
        assertThat(parsed.trustTier()).isEqualTo("T1");
    }

    @Test
    void tokenFromAnotherIssuer_isRejected() {
        // Signed by an "evil" backend with a different issuer but (impossibly) the same secret.
        JwtService evil = new JwtService(props("evil-issuer", "taarifu-clients"));
        String token = evil.issueAccessToken(UUID.randomUUID(), java.util.List.of(), "T1");

        JwtService ours = new JwtService(props("taarifu", "taarifu-clients"));
        assertThatThrownBy(() -> ours.verify(token, TokenType.ACCESS))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void tokenForAnotherAudience_isRejected() {
        JwtService webAdminSigner = new JwtService(props("taarifu", "web-admin"));
        String token = webAdminSigner.issueAccessToken(UUID.randomUUID(), java.util.List.of(), "T1");

        JwtService citizenVerifier = new JwtService(props("taarifu", "citizen-app"));
        assertThatThrownBy(() -> citizenVerifier.verify(token, TokenType.ACCESS))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void refreshTokenCannotSatisfyAccessPath() {
        JwtService service = new JwtService(props("taarifu", "taarifu-clients"));
        String refresh = service.issueRefreshToken(UUID.randomUUID());
        assertThatThrownBy(() -> service.verify(refresh, TokenType.ACCESS))
                .isInstanceOf(JwtVerificationException.class);
    }
}

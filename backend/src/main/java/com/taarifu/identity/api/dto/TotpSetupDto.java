package com.taarifu.identity.api.dto;

/**
 * Response to {@code POST /auth/mfa/totp/setup} — the one-time TOTP enrolment material (N-4,
 * VERIFICATION-DESIGN §7.3).
 *
 * <p>The {@code secret} is returned <b>once</b> for manual entry and is <b>never logged</b> or re-fetched
 * (S-4); the {@code otpauthUri} is scanned into an authenticator app. The secret is stored encrypted in
 * the pending slot until {@code activate} promotes it.</p>
 *
 * @param otpauthUri the {@code otpauth://totp/...} provisioning URI.
 * @param secret     the raw Base32 secret (shown once; never logged).
 */
public record TotpSetupDto(String otpauthUri, String secret) {
}

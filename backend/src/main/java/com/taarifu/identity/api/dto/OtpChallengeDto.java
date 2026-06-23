package com.taarifu.identity.api.dto;

import java.util.UUID;

/**
 * Response handed back after requesting an OTP — the challenge id to verify against (AUTH-DESIGN §3).
 *
 * <p>Carries no PII and no hint of account existence (anti-enumeration). The code itself is delivered
 * out-of-band via SMS and is never present in any API response (S-4).</p>
 *
 * @param challengeId the OTP challenge public id, supplied back on verify.
 */
public record OtpChallengeDto(UUID challengeId) {
}

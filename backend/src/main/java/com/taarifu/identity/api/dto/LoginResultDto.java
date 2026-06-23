package com.taarifu.identity.api.dto;

/**
 * The result of a first-factor login (AUTH-DESIGN §14.1, VERIFICATION-DESIGN §7.1).
 *
 * <p>For a non-staff/non-MFA account this carries the token pair and {@code mfaRequired=false}. For a
 * staff/MFA account the first factor does <b>not</b> issue a token pair: {@code mfaRequired=true} and an
 * {@code mfaToken} (the short-lived {@code MFA_CHALLENGE}) is returned, to be exchanged at
 * {@code POST /auth/login/totp} together with the TOTP code (N-4). Exactly one of {@code tokens} /
 * {@code mfaToken} is present. The {@code mfaToken} is never logged (S-4).</p>
 *
 * @param mfaRequired whether the staff TOTP second factor must be completed before access is granted.
 * @param tokens      the issued token pair, or {@code null} when MFA is required.
 * @param mfaToken    the {@code MFA_CHALLENGE} token, or {@code null} when tokens are issued.
 */
public record LoginResultDto(boolean mfaRequired, TokenPairDto tokens, String mfaToken) {
}

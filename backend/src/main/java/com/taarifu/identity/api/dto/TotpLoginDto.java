package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The staff second-factor login step (N-4, VERIFICATION-DESIGN §7.1): carries the {@code MFA_CHALLENGE}
 * token from the first factor plus the TOTP code. Neither value is logged (S-4).
 *
 * @param mfaToken the short-lived {@code MFA_CHALLENGE} JWT returned by {@code login/password|otp}.
 * @param totp     the 6-digit TOTP code from the authenticator.
 */
public record TotpLoginDto(
        @NotBlank(message = "identity.mfaToken.required") String mfaToken,

        @NotBlank(message = "identity.totp.required")
        @Pattern(regexp = "^\\d{6}$", message = "identity.totp.invalid") String totp
) {
}

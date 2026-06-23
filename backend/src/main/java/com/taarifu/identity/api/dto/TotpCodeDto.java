package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A bare 6-digit TOTP code — used to {@code activate} MFA (N-4, VERIFICATION-DESIGN §7.3). The code is
 * never logged (S-4).
 *
 * @param totp the 6-digit TOTP code from the authenticator.
 */
public record TotpCodeDto(
        @NotBlank(message = "identity.totp.required")
        @Pattern(regexp = "^\\d{6}$", message = "identity.totp.invalid")
        String totp
) {
}

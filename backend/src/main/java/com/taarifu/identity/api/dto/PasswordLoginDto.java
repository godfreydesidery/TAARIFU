package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Password login request by phone or email (AUTH-DESIGN §4.1).
 *
 * <p>The failure response is uniform regardless of which field is wrong (anti-enumeration, PRD §18);
 * the password is never logged (S-4).</p>
 *
 * @param accountKey the phone (E.164, starts with {@code +}) or email identifying the account.
 * @param password   the account password.
 */
public record PasswordLoginDto(
        @NotBlank(message = "identity.accountKey.required")
        String accountKey,

        @NotBlank(message = "identity.password.required")
        String password
) {
}

package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request to send an OTP to a phone (signup or login) — AUTH-DESIGN §3, §4.2.
 *
 * <p>Validated at the edge (CLAUDE.md §8). The response is always non-committal (a challenge id) so it
 * never reveals whether the phone already has an account (anti-enumeration, PRD §18).</p>
 *
 * @param phone the destination phone in E.164 (e.g. {@code +255700000001}). Never logged (S-4).
 */
public record OtpRequestDto(
        @NotBlank(message = "identity.phone.required")
        @Pattern(regexp = "^\\+\\d{9,15}$", message = "identity.phone.invalid")
        String phone
) {
}

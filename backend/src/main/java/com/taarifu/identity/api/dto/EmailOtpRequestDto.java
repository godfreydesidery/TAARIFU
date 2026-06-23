package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to issue an EMAIL VERIFY OTP for the caller's contact-channel verification (T2 path,
 * VERIFICATION-DESIGN §3).
 *
 * @param email the destination email to verify (the cheaper OTP channel — AUTH-DESIGN §15).
 */
public record EmailOtpRequestDto(
        @NotBlank(message = "identity.email.required")
        @Email(message = "identity.email.invalid")
        @Size(max = 254, message = "identity.email.invalid")
        String email
) {
}

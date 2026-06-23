package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Request to complete signup / OTP-login by submitting the code for a challenge (AUTH-DESIGN §3, §4.2).
 *
 * @param challengeId the challenge public id returned by the request step.
 * @param code        the numeric OTP the user received (never logged — S-4).
 */
public record VerifyOtpDto(
        @NotNull(message = "identity.challengeId.required")
        UUID challengeId,

        @NotBlank(message = "identity.otp.required")
        @Pattern(regexp = "^\\d{4,8}$", message = "identity.otp.invalid")
        String code
) {
}

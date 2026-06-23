package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh / logout request carrying the raw refresh token (AUTH-DESIGN §5.1).
 *
 * @param refreshToken the rotating refresh JWT to rotate (refresh) or revoke (logout). Never logged.
 */
public record RefreshRequestDto(
        @NotBlank(message = "identity.refreshToken.required")
        String refreshToken
) {
}

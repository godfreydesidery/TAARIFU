package com.taarifu.tokens.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request DTO to create or update a behaviour reward config (admin, PRD §23.4 — anti-farming caps).
 *
 * @param behaviour   {@code RewardBehaviour} name (validated in the service); required.
 * @param grantAmount tokens granted per occurrence; &gt; 0.
 * @param capCount    max grants per period (anti-farming); ≥ 0.
 * @param capPeriod   {@code QuotaPeriod} name for the cap window; required.
 */
public record UpsertTokenRewardRequest(
        @NotBlank @Size(max = 48) String behaviour,
        @Positive long grantAmount,
        @PositiveOrZero int capCount,
        @NotBlank @Size(max = 16) String capPeriod
) {
}

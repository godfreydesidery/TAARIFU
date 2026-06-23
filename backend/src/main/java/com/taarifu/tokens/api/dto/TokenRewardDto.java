package com.taarifu.tokens.api.dto;

import java.util.UUID;

/**
 * Response DTO for a {@code TokenReward} config row (PRD §23.4 admin config).
 *
 * @param id          the reward's public id.
 * @param behaviour   the triggering civic behaviour.
 * @param grantAmount tokens granted per occurrence.
 * @param capCount    anti-farming cap per period.
 * @param capPeriod   the cap window (e.g. LIFETIME).
 * @param active      whether currently active.
 */
public record TokenRewardDto(
        UUID id,
        String behaviour,
        long grantAmount,
        int capCount,
        String capPeriod,
        boolean active
) {
}

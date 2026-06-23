package com.taarifu.tokens.api.dto;

import java.util.UUID;

/**
 * Response DTO for an {@code ActionCostPolicy} config row (PRD §23.4 admin config).
 *
 * @param id              the policy's public id.
 * @param actionCode      the metered action.
 * @param roleName        the role this applies to, or {@code null} for the default policy.
 * @param tokenCost       per-use token cost after free quota.
 * @param freeQuotaPeriod free-allowance recurrence (DAILY/WEEKLY/MONTHLY/LIFETIME).
 * @param freeQuotaCount  free uses per period.
 * @param policyVersion   version of the policy for its key.
 * @param active          whether currently in force.
 */
public record ActionCostPolicyDto(
        UUID id,
        String actionCode,
        String roleName,
        long tokenCost,
        String freeQuotaPeriod,
        int freeQuotaCount,
        int policyVersion,
        boolean active
) {
}

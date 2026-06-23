package com.taarifu.tokens.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request DTO to create or update an action cost/quota policy (admin, PRD §23.4 — admin-tunable, versioned).
 *
 * <p>Validation at the edge (CLAUDE.md §8): action code is mandatory; costs/quotas are non-negative; the
 * free-quota period must be a valid {@code QuotaPeriod} name (checked in the service). A {@code null}
 * {@code roleName} means the action's default/fallback policy.</p>
 *
 * @param actionCode      the metered action (e.g. {@code FILE_REPORT}); required.
 * @param roleName        the role this applies to, or {@code null}/blank for the default policy.
 * @param tokenCost       per-use token cost after free quota; ≥ 0.
 * @param freeQuotaPeriod {@code QuotaPeriod} name (DAILY/WEEKLY/MONTHLY/LIFETIME); required.
 * @param freeQuotaCount  free uses per period; ≥ 0.
 */
public record UpsertActionCostPolicyRequest(
        @NotBlank @Size(max = 64) String actionCode,
        @Size(max = 32) String roleName,
        @PositiveOrZero long tokenCost,
        @NotBlank @Size(max = 16) String freeQuotaPeriod,
        @PositiveOrZero int freeQuotaCount
) {
}

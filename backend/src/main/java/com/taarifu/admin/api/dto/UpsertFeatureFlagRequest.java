package com.taarifu.admin.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin request to create or update a feature flag (M14, UC-H07).
 *
 * <p>Responsibility: the full desired state of a flag. The service upserts by {@link #key} (unique). The
 * rollout percentage is range-validated 0–100 at the edge to match the DB CHECK constraint.</p>
 *
 * @param key               the stable machine key (required; module-namespaced).
 * @param description       human purpose (max 255), or {@code null}.
 * @param enabled           the master switch.
 * @param rolloutPercentage staged-rollout percentage 0–100.
 */
public record UpsertFeatureFlagRequest(
        @NotBlank @Size(max = 96) String key,
        @Size(max = 255) String description,
        boolean enabled,
        @Min(0) @Max(100) int rolloutPercentage) {
}

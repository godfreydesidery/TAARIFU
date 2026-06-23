package com.taarifu.reporting.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO to edit an existing {@link com.taarifu.reporting.domain.model.IssueCategory} (UC-B14,
 * ROLE_ADMIN).
 *
 * <p>Responsibility: the validated boundary input for category edits. The {@code code} and {@code parent}
 * are <b>not</b> editable here — the machine code is immutable (clients/imports match on it) and
 * re-parenting is a structural change handled separately if ever needed (KISS).</p>
 *
 * @param name                  new display name (required).
 * @param defaultRoutingLevel   new routing token name (validated in service).
 * @param defaultSlaTtfrMinutes new TTFR in minutes (≥ 1).
 * @param defaultSlaTtrMinutes  new TTR in minutes (≥ 1).
 * @param sensitive             new sensitivity flag.
 * @param forcePrivate          new force-private flag.
 * @param defaultVisibility     new visibility name (validated in service).
 * @param icon                  new UI icon token.
 * @param active                new active flag (retire/restore in the picker).
 */
public record UpdateIssueCategoryDto(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 24) String defaultRoutingLevel,
        @Min(1) @Max(525600) int defaultSlaTtfrMinutes,
        @Min(1) @Max(525600) int defaultSlaTtrMinutes,
        boolean sensitive,
        boolean forcePrivate,
        @NotBlank @Size(max = 16) String defaultVisibility,
        @Size(max = 64) String icon,
        boolean active
) {
}

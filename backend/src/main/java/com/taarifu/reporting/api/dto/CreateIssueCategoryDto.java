package com.taarifu.reporting.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO to create an {@link com.taarifu.reporting.domain.model.IssueCategory} (UC-B14, ROLE_ADMIN).
 *
 * <p>Responsibility: the validated boundary input for category creation. Bean Validation runs at the
 * edge (CLAUDE.md §8) so the service receives a structurally-sound request; semantic checks (duplicate
 * code, parent exists) are the service's job.</p>
 *
 * @param code                  stable machine code; uppercase letters/digits/underscore (matches the seed
 *                              taxonomy convention, e.g. {@code WATER_SANITATION}). Immutable once set.
 * @param name                  Swahili-first display name (required).
 * @param parentId              optional parent category public id, or {@code null} for top-level.
 * @param defaultRoutingLevel   routing token name (must match a {@code RoutingLevel}); validated in service.
 * @param defaultSlaTtfrMinutes default TTFR in minutes (≥ 1).
 * @param defaultSlaTtrMinutes  default TTR in minutes (≥ 1).
 * @param sensitive             anonymity-eligible (D-Q1).
 * @param forcePrivate          force PRIVATE regardless of citizen choice.
 * @param defaultVisibility     visibility name (must match a {@code ReportVisibility}); validated in service.
 * @param icon                  optional UI icon token.
 */
public record CreateIssueCategoryDto(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*",
                message = "code must be UPPER_SNAKE_CASE") String code,
        @NotBlank @Size(max = 160) String name,
        UUID parentId,
        @NotBlank @Size(max = 24) String defaultRoutingLevel,
        @Min(1) @Max(525600) int defaultSlaTtfrMinutes,
        @Min(1) @Max(525600) int defaultSlaTtrMinutes,
        boolean sensitive,
        boolean forcePrivate,
        @NotBlank @Size(max = 16) String defaultVisibility,
        @Size(max = 64) String icon
) {
}

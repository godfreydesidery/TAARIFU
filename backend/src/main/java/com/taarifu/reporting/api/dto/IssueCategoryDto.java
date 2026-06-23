package com.taarifu.reporting.api.dto;

import java.util.UUID;

/**
 * Response DTO for an {@link com.taarifu.reporting.domain.model.IssueCategory} (PRD §9.1, Appendix D).
 *
 * <p>Responsibility: the boundary shape for category reads (the picker and the Admin console). Exposes
 * only the {@code publicId} (never the internal {@code Long id}) and the routing/SLA/sensitivity defaults
 * a client needs to render the picker and show the expected SLA at file time (UC-D01 step 7).</p>
 *
 * @param id                 the category's public id (UUID).
 * @param code               stable machine code.
 * @param name               Swahili-first display name.
 * @param parentId           parent category public id, or {@code null} for a top-level node.
 * @param defaultRoutingLevel default routing token name (Appendix D.1).
 * @param defaultSlaTtfrMinutes default TTFR in minutes.
 * @param defaultSlaTtrMinutes  default TTR in minutes.
 * @param sensitive          {@code true} if anonymity-eligible (D-Q1).
 * @param forcePrivate       {@code true} if reports here are forced PRIVATE.
 * @param defaultVisibility  default visibility name.
 * @param icon               optional UI icon token.
 * @param active             {@code true} if shown in the picker.
 */
public record IssueCategoryDto(
        UUID id,
        String code,
        String name,
        UUID parentId,
        String defaultRoutingLevel,
        int defaultSlaTtfrMinutes,
        int defaultSlaTtrMinutes,
        boolean sensitive,
        boolean forcePrivate,
        String defaultVisibility,
        String icon,
        boolean active
) {
}

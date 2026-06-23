package com.taarifu.geography.api.dto;

import java.util.UUID;

/**
 * Response DTO for a District (Wilaya) — a {@link com.taarifu.geography.domain.model.Location} of type
 * {@code DISTRICT} (PRD §9.0).
 *
 * <p>Responsibility: the boundary shape for {@code GET /regions/{publicId}/districts}. Carries the
 * parent region's public id so a client can render breadcrumbs without a second call.</p>
 *
 * @param id       the district's public id (UUID).
 * @param code     official district code.
 * @param name     district display name (e.g. "Rombo").
 * @param regionId the parent region's public id.
 */
public record DistrictDto(
        UUID id,
        String code,
        String name,
        UUID regionId
) {
}

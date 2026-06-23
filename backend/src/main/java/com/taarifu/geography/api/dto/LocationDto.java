package com.taarifu.geography.api.dto;

import java.util.UUID;

/**
 * Generic response DTO for any administrative {@link com.taarifu.geography.domain.model.Location}
 * node (ARCHITECTURE.md §5, CLAUDE.md §8).
 *
 * <p>Responsibility: the boundary representation of a location — exposes only the {@code publicId}
 * (never the internal {@code Long id}, ADR-0006), the civic {@code code}/{@code name}, the level, and
 * the parent's public id. Used for {@code GET /locations/{publicId}} and as the element type for the
 * level-specific list DTOs ({@link RegionDto}, {@link DistrictDto}, …) which are thin aliases for
 * documentation/typing clarity.</p>
 *
 * <p>WHY a DTO (not the entity): entities never leave a module (CLAUDE.md §8); the DTO keeps the wire
 * contract stable and lean for low-bandwidth clients (PRD §15) and hides persistence internals.</p>
 *
 * @param id       the location's public id (UUID).
 * @param code     official administrative code.
 * @param name     display name (local civic vocabulary).
 * @param type     hierarchy level name (e.g. {@code REGION}, {@code WARD}).
 * @param status   civic lifecycle status name.
 * @param parentId the parent location's public id, or {@code null} for a region.
 */
public record LocationDto(
        UUID id,
        String code,
        String name,
        String type,
        String status,
        UUID parentId
) {
}

package com.taarifu.geography.api.dto;

import java.util.UUID;

/**
 * Response DTO for a Region (Mkoa) — a {@link com.taarifu.geography.domain.model.Location} of type
 * {@code REGION} (PRD §9.0, §11 M1).
 *
 * <p>Responsibility: the boundary shape for the {@code GET /regions} list and {@code GET
 * /regions/{publicId}} lookup. A region has no administrative parent, so (unlike {@link LocationDto})
 * it carries no {@code parentId} — a level-named type keeps the OpenAPI contract and generated clients
 * self-documenting (CLAUDE.md §8).</p>
 *
 * @param id   the region's public id (UUID).
 * @param code official region code.
 * @param name region display name (e.g. "Kilimanjaro").
 */
public record RegionDto(
        UUID id,
        String code,
        String name
) {
}

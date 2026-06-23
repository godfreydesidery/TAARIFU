package com.taarifu.geography.api.dto;

import java.util.UUID;

/**
 * Response DTO for a Village (Kijiji) or Mtaa — a
 * {@link com.taarifu.geography.domain.model.Location} of type {@code VILLAGE} or {@code MTAA}
 * (PRD §9.0).
 *
 * <p>Responsibility: the boundary shape for the optional sub-ward level used for finer pin precision.
 * The {@code type} field distinguishes rural ({@code VILLAGE}) from urban ({@code MTAA}) since they are
 * peers at the same level (PRD §9.0).</p>
 *
 * @param id       the unit's public id (UUID).
 * @param code     official code.
 * @param name     display name.
 * @param type     the level name ({@code VILLAGE} or {@code MTAA}).
 * @param wardId   the parent ward's public id.
 */
public record VillageDto(
        UUID id,
        String code,
        String name,
        String type,
        UUID wardId
) {
}

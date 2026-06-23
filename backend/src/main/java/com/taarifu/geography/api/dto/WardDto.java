package com.taarifu.geography.api.dto;

import java.util.UUID;

/**
 * Response DTO for a Ward (Kata) — a {@link com.taarifu.geography.domain.model.Location} of type
 * {@code WARD} (PRD §9.0).
 *
 * <p>Responsibility: the boundary shape for ward reads and as the unit returned by GPS→ward resolution
 * and "constituency's current wards". The ward is the <b>minimum pin granularity</b> — enough to
 * derive councillor + constituency + report routing (PRD §9.0).</p>
 *
 * @param id       the ward's public id (UUID).
 * @param code     official ward code.
 * @param name     ward display name (e.g. "Mengwe").
 * @param parentId the parent location's public id (a council or, where present, a division).
 */
public record WardDto(
        UUID id,
        String code,
        String name,
        UUID parentId
) {
}

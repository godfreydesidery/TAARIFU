package com.taarifu.geography.api.dto;

import java.util.UUID;

/**
 * Response DTO for a Council / LGA (Halmashauri) — a
 * {@link com.taarifu.geography.domain.model.Location} of type {@code COUNCIL} (PRD §9.0 D14).
 *
 * <p>Responsibility: the boundary shape for council reads. The Council/LGA level was added per D14
 * because services and officials sit there (report routing happens at this level), so it is a
 * first-class DTO.</p>
 *
 * @param id         the council's public id (UUID).
 * @param code       official council/LGA code.
 * @param name       council display name (e.g. "Rombo District Council").
 * @param districtId the parent district's public id.
 */
public record CouncilDto(
        UUID id,
        String code,
        String name,
        UUID districtId
) {
}

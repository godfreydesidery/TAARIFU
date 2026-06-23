package com.taarifu.institutions.api.dto;

import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.institutions.domain.model.PoliticalParty} (PRD §9.1, §22.6).
 *
 * <p>Responsibility: the public boundary shape for the party directory. Exposes only the {@code publicId}
 * (never the internal numeric id, ADR-0006) and reference attributes — no PII, no internal audit fields.</p>
 *
 * @param id           the party's public id.
 * @param code         official party code.
 * @param name         full registered name.
 * @param abbreviation short form/acronym, or {@code null}.
 * @param ideology     stated ideology, or {@code null}.
 * @param foundedYear  founding year, or {@code null}.
 * @param logoRef      object-store reference to the logo asset, or {@code null}.
 * @param status       civic lifecycle status name (ACTIVE/INACTIVE).
 * @param contacts     public contact details, or {@code null}.
 */
public record PoliticalPartyDto(
        UUID id,
        String code,
        String name,
        String abbreviation,
        String ideology,
        Integer foundedYear,
        String logoRef,
        String status,
        String contacts
) {
}

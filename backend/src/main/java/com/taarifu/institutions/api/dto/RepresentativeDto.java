package com.taarifu.institutions.api.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Full response DTO for a representative profile (PRD §9.1, §22.6; UC-C02 view representative profile).
 *
 * <p>Responsibility: the complete public profile of a representative — identity link, seat, party,
 * parliament term/role, lifecycle, and biography. Exposes only public ids and reference attributes; it
 * carries no private PII (the linked {@code profileId} is a public id, not contact/ID data — PRD §9.0
 * privacy invariant).</p>
 *
 * <p>Geographic and party/parliament references are denormalised to {@code (id, name)} pairs so a client
 * renders the profile without follow-up lookups while still being able to deep-link by id.</p>
 *
 * @param id                  the representative's public id.
 * @param profileId           linked identity Profile public id, or {@code null} if not yet linked.
 * @param type                representative kind (MP/COUNCILLOR/WARD_EXEC).
 * @param mandate             how the seat is held.
 * @param status              lifecycle status (PENDING_VERIFICATION/SITTING/FORMER).
 * @param constituencyId      constituency public id, or {@code null}.
 * @param constituencyName    constituency name, or {@code null}.
 * @param wardId              ward public id, or {@code null}.
 * @param wardName            ward name, or {@code null}.
 * @param partyId             party public id, or {@code null}.
 * @param partyName           party name, or {@code null}.
 * @param partyAbbrev         party abbreviation, or {@code null}.
 * @param legislature         legislature name.
 * @param parliamentId        parliament term public id, or {@code null}.
 * @param parliamentName      parliament term name, or {@code null}.
 * @param parliamentRoleId    additional parliamentary office public id, or {@code null}.
 * @param parliamentRoleName  additional parliamentary office name, or {@code null}.
 * @param electedAt           election/seating date, or {@code null}.
 * @param bio                 public biography, or {@code null}.
 */
public record RepresentativeDto(
        UUID id,
        UUID profileId,
        String type,
        String mandate,
        String status,
        UUID constituencyId,
        String constituencyName,
        UUID wardId,
        String wardName,
        UUID partyId,
        String partyName,
        String partyAbbrev,
        String legislature,
        UUID parliamentId,
        String parliamentName,
        UUID parliamentRoleId,
        String parliamentRoleName,
        LocalDate electedAt,
        String bio
) {
}

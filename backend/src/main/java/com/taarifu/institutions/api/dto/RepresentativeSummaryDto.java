package com.taarifu.institutions.api.dto;

import java.util.UUID;

/**
 * Lean response DTO for a representative card — used by "find my representatives" and directory/search
 * lists (PRD §22.6 first-class find-my-rep flow; §15 feature-phone payload budget).
 *
 * <p>Responsibility: the smallest useful representation of a representative — enough to render a card and
 * a follow button without a second round-trip. WHY a separate lean DTO from {@link RepresentativeDto}:
 * find-my-rep and directory lists are the highest-traffic, most data-cost-sensitive reads (often over
 * 2G); they must not carry the full bio/parliament-role payload (PRD §15). Names are denormalised inline
 * so a feature-phone client needs no follow-up lookups.</p>
 *
 * @param id              the representative's public id.
 * @param profileId       linked identity Profile public id (for follow/profile linking), or {@code null}.
 * @param type            representative kind (MP/COUNCILLOR/WARD_EXEC).
 * @param mandate         how the seat is held.
 * @param status          lifecycle status (SITTING/FORMER/PENDING_VERIFICATION) — drives the historical badge.
 * @param partyName       affiliated party name, or {@code null} for an independent.
 * @param partyAbbrev     party abbreviation, or {@code null}.
 * @param constituencyId  constituency public id, or {@code null}.
 * @param constituencyName constituency name, or {@code null}.
 * @param wardId          ward public id, or {@code null}.
 * @param wardName        ward name, or {@code null}.
 * @param legislature     legislature name.
 */
public record RepresentativeSummaryDto(
        UUID id,
        UUID profileId,
        String type,
        String mandate,
        String status,
        String partyName,
        String partyAbbrev,
        UUID constituencyId,
        String constituencyName,
        UUID wardId,
        String wardName,
        String legislature
) {
}

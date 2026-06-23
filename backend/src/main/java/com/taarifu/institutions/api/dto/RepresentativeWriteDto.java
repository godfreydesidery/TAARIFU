package com.taarifu.institutions.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Admin write DTO for creating or updating a
 * {@link com.taarifu.institutions.domain.model.Representative} (PRD §9.1; UC-C04 admin create/link
 * representative, UC-C08 transition status).
 *
 * <p>Responsibility: the validated request body for admin representative CRUD. References to geography
 * and party/parliament are by <b>public id</b> (resolved server-side to real FKs); the linked identity
 * account is the {@code profileId} public id only (cross-module-by-id, never an identity import —
 * ARCHITECTURE.md §3.2).</p>
 *
 * <p>WHY the geographic ids are optional here even though some mandates require one: the structural
 * rule (constituency-mandate ⇒ constituency present; ward-mandate ⇒ ward present; special-seats/nominated
 * ⇒ neither) is a <b>cross-field</b> invariant validated in the application service (with a typed,
 * localised error) and backstopped by a DB CHECK — a single {@code @NotNull} cannot express it.</p>
 *
 * @param profileId        linked identity Profile public id, or {@code null} for an onboarding placeholder.
 * @param type             representative kind name (MP/COUNCILLOR/WARD_EXEC) — required.
 * @param mandate          mandate name (CONSTITUENCY/SPECIAL_SEATS/NOMINATED/COUNCILLOR_WARD) — required.
 * @param constituencyId   constituency public id (required iff mandate=CONSTITUENCY), else {@code null}.
 * @param wardId           ward public id (required iff mandate=COUNCILLOR_WARD), else {@code null}.
 * @param partyId          party public id, or {@code null} for an independent.
 * @param legislature      legislature name (UNION_PARLIAMENT/ZANZIBAR_HOR); defaults to UNION_PARLIAMENT when blank.
 * @param parliamentId     parliament term public id, or {@code null}.
 * @param parliamentRoleId additional parliamentary office public id, or {@code null}.
 * @param status           lifecycle status name (PENDING_VERIFICATION/SITTING/FORMER); defaults to PENDING_VERIFICATION when blank.
 * @param electedAt        election/seating date, or {@code null}.
 * @param bio              public biography, or {@code null}.
 */
public record RepresentativeWriteDto(
        UUID profileId,
        @NotBlank @Size(max = 16) String type,
        @NotBlank @Size(max = 24) String mandate,
        UUID constituencyId,
        UUID wardId,
        UUID partyId,
        @Size(max = 24) String legislature,
        UUID parliamentId,
        UUID parliamentRoleId,
        @Size(max = 24) String status,
        LocalDate electedAt,
        @Size(max = 4000) String bio
) {
}

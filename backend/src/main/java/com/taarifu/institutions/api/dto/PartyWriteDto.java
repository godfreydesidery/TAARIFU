package com.taarifu.institutions.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin write DTO for creating or updating a {@link com.taarifu.institutions.domain.model.PoliticalParty}
 * (PRD §9.1; UC-B11 CRUD Party).
 *
 * <p>Responsibility: the validated request body for admin party CRUD. WHY validation lives on the DTO
 * (Bean Validation) not in the service: the boundary rejects malformed input uniformly via the
 * {@code GlobalExceptionHandler}, which collapses field errors into {@code data.errors[]} (PRD §17). On
 * update, the {@code code} is treated as immutable by the service (it is the idempotent identity key);
 * changing it is rejected to avoid orphaning seed/import references.</p>
 *
 * @param code         official party code (required, unique); immutable on update.
 * @param name         full registered name (required).
 * @param abbreviation short form/acronym, or {@code null}.
 * @param ideology     stated ideology, or {@code null}.
 * @param foundedYear  founding year, or {@code null}.
 * @param logoRef      object-store reference to the logo asset, or {@code null}.
 * @param status       civic lifecycle status name (ACTIVE/INACTIVE); defaults to ACTIVE when blank.
 * @param contacts     public contact details, or {@code null}.
 */
public record PartyWriteDto(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 32) String abbreviation,
        @Size(max = 160) String ideology,
        Integer foundedYear,
        @Size(max = 512) String logoRef,
        @Size(max = 16) String status,
        @Size(max = 512) String contacts
) {
}

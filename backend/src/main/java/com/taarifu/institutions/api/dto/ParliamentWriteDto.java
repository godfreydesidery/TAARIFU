package com.taarifu.institutions.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Admin write DTO for creating or updating a {@link com.taarifu.institutions.domain.model.Parliament}
 * term (PRD §9.1; UC-B12 CRUD Parliament).
 *
 * <p>Responsibility: the validated request body for admin parliament CRUD. WHY {@code current} is set
 * through this DTO but enforced singular by the DB: marking a term current must atomically be the only
 * current term of its legislature; the service clears the prior current term, and the partial-unique
 * index is the backstop against races (see migration).</p>
 *
 * @param termNumber  the term/session number (required).
 * @param name        term display name (required).
 * @param legislature legislature name (UNION_PARLIAMENT/ZANZIBAR_HOR); defaults to UNION_PARLIAMENT when blank.
 * @param startDate   inclusive start date (required).
 * @param endDate     exclusive end date, or {@code null} if ongoing.
 * @param current     whether this is the currently-sitting term of its legislature.
 */
public record ParliamentWriteDto(
        @NotNull Integer termNumber,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 24) String legislature,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        boolean current
) {
}

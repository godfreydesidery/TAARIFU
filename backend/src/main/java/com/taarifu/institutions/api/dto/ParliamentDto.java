package com.taarifu.institutions.api.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response DTO for a {@link com.taarifu.institutions.domain.model.Parliament} term (PRD §9.1, §22.6).
 *
 * <p>Responsibility: the public boundary shape for the parliament directory. Exposes only the
 * {@code publicId} and reference attributes.</p>
 *
 * @param id          the term's public id.
 * @param termNumber  the term/session number.
 * @param name        term display name.
 * @param legislature legislature name (UNION_PARLIAMENT/ZANZIBAR_HOR).
 * @param startDate   inclusive start date.
 * @param endDate     exclusive end date, or {@code null} if ongoing.
 * @param current     whether this is the currently-sitting term of its legislature.
 */
public record ParliamentDto(
        UUID id,
        Integer termNumber,
        String name,
        String legislature,
        LocalDate startDate,
        LocalDate endDate,
        boolean current
) {
}

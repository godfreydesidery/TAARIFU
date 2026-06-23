package com.taarifu.accountability.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to create/record an {@link com.taarifu.accountability.domain.model.Attendance} row
 * (PRD §10 Epic M6, US-6.1; D-Q4 curated authorship).
 *
 * <p>Accepted only from an authorised author / {@code ROLE_ADMIN}. A repeat (rep, session) is a
 * {@code 409 CONFLICT} (one authoritative row per session).</p>
 *
 * @param representativeId the subject representative's public id (required).
 * @param sessionRef       the session/sitting reference (required).
 * @param present          whether the representative attended (required).
 */
public record CreateAttendanceDto(
        @NotNull(message = "accountability.representative.required") UUID representativeId,
        @NotBlank(message = "accountability.attendance.session.required")
        @Size(max = 120, message = "accountability.attendance.session.tooLong") String sessionRef,
        @NotNull(message = "accountability.attendance.present.required") Boolean present
) {
}

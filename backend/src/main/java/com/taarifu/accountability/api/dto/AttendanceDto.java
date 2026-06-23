package com.taarifu.accountability.api.dto;

import java.util.UUID;

/**
 * Public response DTO for an {@link com.taarifu.accountability.domain.model.Attendance} row
 * (PRD §10 Epic M6, US-6.1).
 *
 * @param id               the attendance row's public id.
 * @param representativeId the subject representative's public id (institutions module).
 * @param sessionRef       the session/sitting reference.
 * @param present          whether the representative attended.
 */
public record AttendanceDto(
        UUID id,
        UUID representativeId,
        String sessionRef,
        boolean present
) {
}

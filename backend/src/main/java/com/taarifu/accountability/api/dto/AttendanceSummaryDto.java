package com.taarifu.accountability.api.dto;

import java.util.UUID;

/**
 * Public response DTO for a representative's <b>computed</b> attendance aggregate (PRD §10 Epic M6,
 * US-6.1).
 *
 * <p>Derived from the append-only {@link com.taarifu.accountability.domain.model.Attendance} rows so it
 * cannot drift from the underlying records.</p>
 *
 * @param representativeId the subject representative's public id.
 * @param present          number of attended sessions.
 * @param total            total number of recorded sessions.
 * @param rate             present/total as a 0..1 fraction, or {@code null} when {@code total} is 0.
 */
public record AttendanceSummaryDto(
        UUID representativeId,
        long present,
        long total,
        Double rate
) {
}

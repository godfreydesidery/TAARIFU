package com.taarifu.reporting.api.dto;

/**
 * One {@code (status, count)} pair in the reporting module's headline counts, published on
 * {@link com.taarifu.reporting.api.ReportQueryApi#reportCountsByStatus()} for the admin dashboard
 * (M14, UC-H06; PRD §10).
 *
 * <p>Responsibility: a transport-neutral count of reports in a given lifecycle status. WHY the
 * {@code status} is the enum <i>name</i> as a {@code String} (not the {@code ReportStatus} enum):
 * the enum is a reporting-internal domain type that must not cross the module boundary as a type
 * (CLAUDE.md §8); the name is a stable, language-independent discriminator the admin console branches on.
 * Counts only ever cross the boundary, never PII (PRD §18).</p>
 *
 * @param status the lifecycle status name (e.g. {@code NEW}, {@code IN_PROGRESS}, {@code CLOSED}).
 * @param count  the number of (non-deleted) reports in that status; always {@code >= 0}.
 */
public record ReportStatusCount(String status, long count) {

    /**
     * Compact canonical constructor enforcing the invariants so a malformed count never reaches the
     * dashboard.
     *
     * @throws IllegalArgumentException if {@code status} is null/blank or {@code count} is negative.
     */
    public ReportStatusCount {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("ReportStatusCount status must be present");
        }
        if (count < 0) {
            throw new IllegalArgumentException("ReportStatusCount count must be non-negative");
        }
    }
}

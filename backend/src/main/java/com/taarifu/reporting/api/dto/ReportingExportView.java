package com.taarifu.reporting.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reporting's minimised slice of a data-subject ACCESS export — the issues the subject filed and the
 * timeline entries they authored (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: the boundary shape {@code ReportingExportContributor} returns for the privacy module's
 * export aggregation. It is the subject's <b>own</b> reporting footprint, returned to the subject.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18):</b> the export lists the subject's <i>own</i> reports (ticket code,
 * status, civic title — the substance they wrote) and their <i>own</i> authored timeline messages. It does
 * NOT enumerate other reporters' data, internal responder notes that are not the subject's, exact incident
 * geo-points, or attachment bytes (those are minimised to a count / served separately via media). The
 * subject identifies themselves by their authenticated account; this DTO carries no national/voter ID.</p>
 *
 * @param reports        the subject's filed reports (may be empty).
 * @param authoredEvents the timeline entries the subject authored as the acting citizen (may be empty).
 */
public record ReportingExportView(
        List<ReportedIssue> reports,
        List<AuthoredEvent> authoredEvents) {

    /**
     * One report the subject filed, minimised to the civic substance they own.
     *
     * @param reportPublicId the report's public id (the subject's own tracking handle).
     * @param code           the human ticket code ({@code TAR-YYYY-NNNNNN}).
     * @param title          the citizen-written title (their civic content).
     * @param status         the lifecycle status name.
     * @param visibility     the report visibility name (PUBLIC/PRIVATE).
     * @param filedAt        when the report was filed (UTC).
     */
    public record ReportedIssue(
            UUID reportPublicId,
            String code,
            String title,
            String status,
            String visibility,
            Instant filedAt) {
    }

    /**
     * One timeline entry the subject authored (e.g. a comment or a confirm/dispute), minimised.
     *
     * @param reportPublicId the report the entry belongs to.
     * @param eventType      the kind of timeline entry.
     * @param message        the body the subject wrote (their own content), or {@code null}.
     * @param occurredAt     when the entry was recorded (UTC).
     */
    public record AuthoredEvent(
            UUID reportPublicId,
            String eventType,
            String message,
            Instant occurredAt) {
    }
}

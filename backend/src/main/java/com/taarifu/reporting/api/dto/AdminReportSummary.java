package com.taarifu.reporting.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin/owner-grade report queue — a PII-minimised case summary published on
 * {@link com.taarifu.reporting.api.ReportQueryApi} for the back-office console (M14; PRD §10 US-3.4).
 *
 * <p>Responsibility: the boundary shape for a staff queue row. It carries the operational fields a triager
 * sorts/triages on — ticket {@code code}, category, title, ward, lifecycle {@code status}/{@code priority},
 * SLA {@code dueAt} + a derived {@code slaBreached} flag, the assigned-responder reference, and the filed
 * instant — and <b>deliberately omits</b> the description body, the reporter linkage, and the precise
 * geo-point. WHY the omissions (data minimisation, PRD §18 / PDPA): a queue row is for routing/triage, not
 * a data export; the reporter's identity and exact location never belong in a list. The full staff detail
 * (with the internal timeline) is fetched separately via
 * {@link com.taarifu.reporting.api.ReportQueryApi#adminDetail(java.util.UUID)} for one case at a time.</p>
 *
 * <p>The {@code title} is citizen-authored free text and is included because a queue with no human label is
 * unusable for triage; it is not PII by construction (the reporter is told not to put identifying detail in
 * the title, and sensitive categories are filed anonymously, D-Q1). {@code anonymous} surfaces whether the
 * case has any reporter linkage at all, so staff can apply the sensitive-handling path without ever seeing
 * who filed it.</p>
 *
 * @param id           the report's public id.
 * @param code         the human ticket code ({@code TAR-YYYY-NNNNNN}).
 * @param categoryId   the issue category public id.
 * @param categoryName the issue category display name.
 * @param title        the citizen title/summary.
 * @param wardId       the resolved ward (Kata) public id.
 * @param status       the lifecycle status name.
 * @param priority     the priority name.
 * @param dueAt        the SLA due instant, or {@code null} if none was set.
 * @param slaBreached  {@code true} if the case is still active and {@code dueAt} is in the past.
 * @param assignedResponderId the assigned responder's public id, or {@code null} if unassigned.
 * @param anonymous    {@code true} if the report has no reporter linkage (anonymous sensitive filing, D-Q1).
 * @param createdAt    the filed instant (UTC).
 */
public record AdminReportSummary(
        UUID id,
        String code,
        UUID categoryId,
        String categoryName,
        String title,
        UUID wardId,
        String status,
        String priority,
        Instant dueAt,
        boolean slaBreached,
        UUID assignedResponderId,
        boolean anonymous,
        Instant createdAt
) {
}

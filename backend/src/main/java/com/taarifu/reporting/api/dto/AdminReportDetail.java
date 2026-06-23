package com.taarifu.reporting.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The staff case-detail view of one report — the full operational record published on
 * {@link com.taarifu.reporting.api.ReportQueryApi#adminDetail(UUID)} for the back-office console (M14;
 * PRD §10 US-3.4 "public vs internal notes", §24.3 owner view).
 *
 * <p>Responsibility: the boundary shape a triager/owner sees for a single case. Unlike the citizen-facing
 * {@link ReportDto}, it includes the <b>full</b> case {@link #timeline} — public <i>and</i> internal
 * responder notes (US-3.4) — because the operator is authorised to see the internal record; the
 * citizen/public views never receive internal events (defence-in-depth: those views query the
 * public-only timeline). It carries the case's lifecycle/SLA state and the assigned-responder reference so
 * the console can render the case without a second round-trip.</p>
 *
 * <h3>Privacy boundary (PRD §18 / PDPA, D-Q1)</h3>
 * <ul>
 *   <li><b>No reporter PII:</b> the reporter is surfaced only as the {@code anonymous} flag — never the
 *       reporter's profile id, phone, or {@code idNo}. Staff triage the case, they do not need to know who
 *       filed it; sensitive cases are filed with no linkage at all (D-Q1).</li>
 *   <li><b>No precise geo-point:</b> only the ward (Kata) is exposed — the precise incident point is
 *       coarsened away at the boundary for the same reason it is dropped from the public projection
 *       (PRD §25.3, Appendix D.4). Routing/triage is ward-grained.</li>
 *   <li><b>Internal timeline IS included</b> — this DTO is returned only to {@code ADMIN}/{@code MODERATOR}
 *       endpoints (method-secured), so the internal notes are reaching an authorised operator.</li>
 * </ul>
 *
 * @param id                  the report's public id.
 * @param code                the human ticket code ({@code TAR-YYYY-NNNNNN}).
 * @param categoryId          the issue category public id.
 * @param categoryName        the issue category display name.
 * @param title               the citizen title.
 * @param description         the citizen description (operator-visible case content; not reporter PII).
 * @param wardId              the resolved ward (Kata) public id.
 * @param constituencyId      the constituency (Jimbo) public id in effect, or {@code null}.
 * @param visibility          the effective visibility name.
 * @param status              the lifecycle status name.
 * @param priority            the priority name.
 * @param dueAt               the SLA due instant, or {@code null}.
 * @param slaBreached         {@code true} if the case is still active and {@code dueAt} is in the past.
 * @param resolution          the resolution note, or {@code null} if unresolved.
 * @param confirmation        the citizen confirmation outcome: {@code null} pending, {@code true}/{@code false}.
 * @param duplicateOfId       the canonical report public id if a duplicate, else {@code null}.
 * @param assignedResponderId the assigned responder's public id, or {@code null} if unassigned.
 * @param anonymous           {@code true} if the report has no reporter linkage (D-Q1).
 * @param createdAt           the filed instant (UTC).
 * @param timeline            the full case timeline (public + internal events), newest first.
 */
public record AdminReportDetail(
        UUID id,
        String code,
        UUID categoryId,
        String categoryName,
        String title,
        String description,
        UUID wardId,
        UUID constituencyId,
        String visibility,
        String status,
        String priority,
        Instant dueAt,
        boolean slaBreached,
        String resolution,
        Boolean confirmation,
        UUID duplicateOfId,
        UUID assignedResponderId,
        boolean anonymous,
        Instant createdAt,
        List<CaseEventDto> timeline
) {
}

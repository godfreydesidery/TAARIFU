package com.taarifu.reporting.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Full report response DTO for the reporter/owner view (PRD §10 US-3.2, UC-D05).
 *
 * <p>Responsibility: the boundary shape returned to the citizen tracking <b>their own</b> report (or to an
 * authorised responder). It includes the ticket {@code code}, status/priority, SLA {@code dueAt},
 * resolution/confirmation, and the engagement counters. It exposes only {@code publicId}s.</p>
 *
 * <p>WHY there is a separate {@link PublicReportDto}: this DTO may include the reporter's own context and
 * the precise geo-point, which must <b>never</b> appear in a public list (PRD §25.3). The two shapes keep
 * the privacy boundary explicit rather than relying on conditional field-stripping.</p>
 *
 * @param id              the report's public id.
 * @param code            the human ticket code ({@code TAR-YYYY-NNNNNN}); also the anonymous tracking handle.
 * @param categoryId      the issue category public id.
 * @param categoryName    the issue category display name.
 * @param title           the title.
 * @param description     the description.
 * @param wardId          resolved ward public id.
 * @param constituencyId  constituency public id in effect, or {@code null}.
 * @param latitude        incident latitude, or {@code null} if no point was filed.
 * @param longitude       incident longitude, or {@code null} if no point was filed.
 * @param visibility      effective visibility name.
 * @param status          lifecycle status name.
 * @param priority        priority name.
 * @param dueAt           SLA due instant, or {@code null}.
 * @param resolution      resolution note, or {@code null} if unresolved.
 * @param confirmation    citizen confirmation outcome: {@code null} pending, {@code true}/{@code false}.
 * @param duplicateOfId   canonical report public id if a duplicate, else {@code null}.
 * @param upvotes         discovery-reach upvote count.
 * @param followers       discovery-reach follower count.
 * @param anonymous       {@code true} if filed without identity linkage.
 * @param createdAt       filed instant (UTC).
 */
public record ReportDto(
        UUID id,
        String code,
        UUID categoryId,
        String categoryName,
        String title,
        String description,
        UUID wardId,
        UUID constituencyId,
        Double latitude,
        Double longitude,
        String visibility,
        String status,
        String priority,
        Instant dueAt,
        String resolution,
        Boolean confirmation,
        UUID duplicateOfId,
        long upvotes,
        long followers,
        boolean anonymous,
        Instant createdAt
) {
}

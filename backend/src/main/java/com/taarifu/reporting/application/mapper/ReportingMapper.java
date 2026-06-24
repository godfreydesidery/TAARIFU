package com.taarifu.reporting.application.mapper;

import com.taarifu.reporting.api.dto.AdminReportDetail;
import com.taarifu.reporting.api.dto.AdminReportSummary;
import com.taarifu.reporting.api.dto.CaseEventDto;
import com.taarifu.reporting.api.dto.IssueCategoryDto;
import com.taarifu.reporting.api.dto.PublicReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps reporting entities to their boundary DTOs (ARCHITECTURE.md §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer so <b>entities never leave the module</b> and only the
 * {@code publicId} is exposed (ADR-0006). It also owns the privacy projection: {@link #toPublicReportDto}
 * deliberately drops the reporter and the precise geo-point, while {@link #toReportDto} (the owner view)
 * includes the point — the two methods are the explicit privacy boundary (PRD §25.3).</p>
 *
 * <p>WHY a hand-written {@code @Component} mapper (not MapStruct): the mappings are trivial and benefit
 * from explicit, documented null-handling (no parent, no point, anonymous reporter), matching the
 * geography module's established choice (ARCHITECTURE.md §2).</p>
 */
@Component
public class ReportingMapper {

    /**
     * @param category the issue category.
     * @return the category DTO; the parent id is null-safe.
     */
    public IssueCategoryDto toIssueCategoryDto(IssueCategory category) {
        return new IssueCategoryDto(
                category.getPublicId(),
                category.getCode(),
                category.getName(),
                category.getParent() != null ? category.getParent().getPublicId() : null,
                category.getDefaultRoutingLevel().name(),
                category.getDefaultSlaTtfrMinutes(),
                category.getDefaultSlaTtrMinutes(),
                category.isSensitive(),
                category.isForcePrivate(),
                category.getDefaultVisibility().name(),
                category.getIcon(),
                category.isActive());
    }

    /**
     * Maps a report to the full owner/reporter view (includes the precise point — never use for public
     * lists).
     *
     * @param report the report.
     * @return the full {@link ReportDto}.
     */
    public ReportDto toReportDto(Report report) {
        Point point = report.getGeoPoint();
        Double latitude = point != null ? point.getY() : null;
        Double longitude = point != null ? point.getX() : null;
        return new ReportDto(
                report.getPublicId(),
                report.getCode(),
                report.getCategory().getPublicId(),
                report.getCategory().getName(),
                report.getTitle(),
                report.getDescription(),
                report.getReporterWardId(),
                report.getConstituencyId(),
                latitude,
                longitude,
                report.getVisibility().name(),
                report.getStatus().name(),
                report.getPriority().name(),
                report.getDueAt(),
                report.getResolution(),
                report.getConfirmation(),
                report.getDuplicateOfId(),
                report.getUpvotes(),
                report.getFollowers(),
                report.isAnonymous(),
                parseAttachmentRefs(report.getAttachmentRefs()),
                report.getCreatedAt());
    }

    /**
     * Maps a report to the PII-free public projection (drops reporter + precise point; PRD §25.3). Caller
     * must only pass PUBLIC reports (the service/repository enforce this).
     *
     * @param report a PUBLIC report.
     * @return the reduced {@link PublicReportDto}.
     */
    public PublicReportDto toPublicReportDto(Report report) {
        return new PublicReportDto(
                report.getPublicId(),
                report.getCode(),
                report.getCategory().getPublicId(),
                report.getCategory().getName(),
                report.getTitle(),
                report.getReporterWardId(),
                report.getStatus().name(),
                report.getPriority().name(),
                report.getUpvotes(),
                report.getFollowers(),
                report.getCreatedAt());
    }

    /**
     * Maps a report to the admin/owner-grade <b>queue row</b> projection (M14; PRD §10 US-3.4, §24.3).
     *
     * <p>WHY this is distinct from {@link #toReportDto}: the queue row is PII-minimised for a list — it
     * drops the description body, the reporter linkage (only the {@code anonymous} flag), and the precise
     * point (only the ward), keeping the back-office list to routing/triage fields (data minimisation,
     * PRD §18 / PDPA). {@code slaBreached} is computed by the caller against "now" (the entity has no
     * clock) and passed in.</p>
     *
     * @param report      the report.
     * @param slaBreached whether the case is currently SLA-breached (computed by the service).
     * @return the {@link AdminReportSummary} queue row.
     */
    public AdminReportSummary toAdminReportSummary(Report report, boolean slaBreached) {
        return new AdminReportSummary(
                report.getPublicId(),
                report.getCode(),
                report.getCategory().getPublicId(),
                report.getCategory().getName(),
                report.getTitle(),
                report.getReporterWardId(),
                report.getStatus().name(),
                report.getPriority().name(),
                report.getDueAt(),
                slaBreached,
                report.getAssignedResponderId(),
                report.isAnonymous(),
                report.getCreatedAt());
    }

    /**
     * Maps a report + its full timeline to the staff <b>case-detail</b> projection (M14; PRD §10 US-3.4).
     *
     * <p>Includes the case content (description) and the <b>full</b> timeline (public + internal events)
     * because the caller is an authorised operator; still drops the reporter linkage (only {@code anonymous})
     * and the precise point (only the ward) — the operator triages the case, not the citizen (D-Q1, §18).
     * {@code slaBreached} and the mapped {@code timeline} are computed/assembled by the caller and passed
     * in so this mapper stays a pure translation (no repository/clock access).</p>
     *
     * @param report      the report.
     * @param slaBreached whether the case is currently SLA-breached (computed by the service).
     * @param timeline    the full timeline already mapped to DTOs (newest first), public + internal.
     * @return the {@link AdminReportDetail}.
     */
    public AdminReportDetail toAdminReportDetail(Report report, boolean slaBreached,
                                                 List<CaseEventDto> timeline) {
        return new AdminReportDetail(
                report.getPublicId(),
                report.getCode(),
                report.getCategory().getPublicId(),
                report.getCategory().getName(),
                report.getTitle(),
                report.getDescription(),
                report.getReporterWardId(),
                report.getConstituencyId(),
                report.getVisibility().name(),
                report.getStatus().name(),
                report.getPriority().name(),
                report.getDueAt(),
                slaBreached,
                report.getResolution(),
                report.getConfirmation(),
                report.getDuplicateOfId(),
                report.getAssignedResponderId(),
                report.isAnonymous(),
                report.getCreatedAt(),
                timeline);
    }

    /**
     * @param event a case-timeline event.
     * @return the {@link CaseEventDto}.
     */
    public CaseEventDto toCaseEventDto(CaseEvent event) {
        return new CaseEventDto(
                event.getPublicId(),
                event.getEventType().name(),
                event.isPublicEvent(),
                event.getActorProfileId(),
                event.getMessage(),
                event.getCreatedAt());
    }

    /**
     * Parses the report's stored delimited attachment refs into media public-id {@code UUID}s for the owner
     * view. Surfaced ONLY via {@link #toReportDto} (the reporter's own/authorised view), never on
     * {@link #toPublicReportDto} — so an anonymous/sensitive report's attachments respect the report's
     * visibility (PRD §25.3). The bytes themselves stay access-controlled by the media module (a presigned
     * GET only for a scanned-CLEAN object); these ids are not the storage keys and carry no PII.
     *
     * @param attachmentRefs the stored comma-delimited refs, or {@code null}.
     * @return the parsed media public ids (never {@code null}; empty if none); a non-UUID token is skipped
     *         defensively so the read path never throws on legacy/malformed stored data.
     */
    private List<java.util.UUID> parseAttachmentRefs(String attachmentRefs) {
        if (attachmentRefs == null || attachmentRefs.isBlank()) {
            return List.of();
        }
        List<java.util.UUID> ids = new java.util.ArrayList<>();
        for (String token : attachmentRefs.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(java.util.UUID.fromString(trimmed));
            } catch (IllegalArgumentException ignored) {
                // Defensive: a legacy/non-UUID stored ref is skipped rather than failing the read.
            }
        }
        return ids;
    }
}

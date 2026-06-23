package com.taarifu.reporting.application.mapper;

import com.taarifu.reporting.api.dto.CaseEventDto;
import com.taarifu.reporting.api.dto.IssueCategoryDto;
import com.taarifu.reporting.api.dto.PublicReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;

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
}

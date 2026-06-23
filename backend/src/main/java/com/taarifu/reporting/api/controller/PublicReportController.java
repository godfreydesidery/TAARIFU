package com.taarifu.reporting.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.reporting.api.dto.CaseEventDto;
import com.taarifu.reporting.api.dto.PublicReportDto;
import com.taarifu.reporting.application.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, read-only REST surface for PUBLIC reports — the near-me list/map (PRD §10 US-3.7, §25.3).
 *
 * <p>Responsibility: thin HTTP layer over {@link ReportService}'s public-read methods. <b>Only PUBLIC
 * reports</b> are ever returned, projected to the PII-free {@link PublicReportDto} — no reporter id, no
 * precise geo-point, never a PRIVATE/sensitive report (PRD §25.3, Appendix D.4). Reads are
 * {@code permitAll()} (registered as a public path centrally); the service + repository both enforce the
 * visibility filter as defence-in-depth. No business logic (CLAUDE.md §8).</p>
 *
 * <p>WHY a separate controller from {@link ReportController}: the public surface has a different security
 * posture (unauthenticated) and a different (reduced) DTO; separating them keeps the privacy boundary
 * structural rather than a per-field conditional.</p>
 */
@RestController
@RequestMapping("/public/reports")
@Tag(name = "Public Reports", description = "Public near-me reports list/map (PUBLIC only, no reporter PII).")
public class PublicReportController {

    private final ReportService reportService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param reportService report read service.
     * @param responses     envelope builder.
     * @param pageRequests  bounded pageable factory.
     * @param pageMapper    page→meta mapper.
     */
    public PublicReportController(ReportService reportService, ResponseFactory responses,
                                  PageRequestFactory pageRequests, PageMapper pageMapper) {
        this.reportService = reportService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists PUBLIC reports, optionally filtered by ward (US-3.7).
     *
     * @param wardId optional ward {@code publicId} to narrow by.
     * @param page   zero-based page index.
     * @param size   page size.
     * @param sort   sort expression.
     * @return a paged envelope of PII-free {@link PublicReportDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List public reports (near-me)")
    public ApiResponse<java.util.List<PublicReportDto>> list(
            @RequestParam(required = false) UUID wardId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<PublicReportDto> result = reportService.listPublicReports(wardId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches one PUBLIC report (US-3.7). A PRIVATE report is reported as not-found.
     *
     * @param reportId the report's public id.
     * @return an envelope with the PII-free {@link PublicReportDto}.
     */
    @GetMapping("/{reportId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a public report")
    public ApiResponse<PublicReportDto> get(@PathVariable UUID reportId) {
        return responses.ok(reportService.getPublicReport(reportId));
    }

    /**
     * Returns the public timeline of a PUBLIC report (US-3.2/US-3.7); internal notes never included.
     *
     * @param reportId the report's public id.
     * @param page     zero-based page index.
     * @param size     page size.
     * @param sort     sort expression.
     * @return a paged envelope of public {@link CaseEventDto}.
     */
    @GetMapping("/{reportId}/timeline")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a public report's public timeline")
    public ApiResponse<java.util.List<CaseEventDto>> timeline(
            @PathVariable UUID reportId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<CaseEventDto> result = reportService.getPublicReportTimeline(reportId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }
}

package com.taarifu.reporting.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.reporting.api.dto.AddCommentDto;
import com.taarifu.reporting.api.dto.CaseEventDto;
import com.taarifu.reporting.api.dto.ConfirmResolutionDto;
import com.taarifu.reporting.api.dto.FileReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.application.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authenticated citizen REST surface for filing and tracking reports (PRD §10 Epic M3, UC-D01/D05/D11-13).
 *
 * <p>Responsibility: thin HTTP layer over {@link ReportService}. Filing requires <b>T2</b>
 * ({@link RequiresTier} enforced live by the kernel aspect — Appendix A); the sensitive-category
 * anonymity relaxation (T1 sufficient, no reporter linkage) is applied inside the service per the
 * category flags (Appendix D.4, D-Q1). All other endpoints require authentication and operate on the
 * caller's <b>own</b> report only (ownership enforced in the service as not-found, never leaking another
 * citizen's report). No business logic, no transactions (CLAUDE.md §8).</p>
 *
 * <p>WHY the token balance never appears here: per the civic-integrity fence (D18, §23.5) reporting is a
 * civic-core action — it is gated on tier only, never on a token balance, and nothing on this controller
 * reads one.</p>
 */
@RestController
@RequestMapping("/reports")
@Tag(name = "Reports", description = "File and track citizen issue reports (Swahili-first).")
public class ReportController {

    private final ReportService reportService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param reportService report case-management service.
     * @param responses     envelope builder.
     * @param pageRequests  bounded pageable factory.
     * @param pageMapper    page→meta mapper.
     */
    public ReportController(ReportService reportService, ResponseFactory responses,
                            PageRequestFactory pageRequests, PageMapper pageMapper) {
        this.reportService = reportService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Files a report (US-3.1, UC-D01). Requires T2 (the file-report tier floor); anonymity on a sensitive
     * category is honoured in the service.
     *
     * @param request the validated file request.
     * @return {@code 201} + the filed {@link ReportDto}.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T2")
    @Operation(summary = "File an issue report (T2; sensitive categories allow anonymous)")
    public ResponseEntity<ApiResponse<ReportDto>> file(@Valid @RequestBody FileReportDto request) {
        ReportDto created = reportService.fileReport(CurrentUser.requirePublicId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Lists the caller's own reports (US-3.2).
     *
     * @param page zero-based page index.
     * @param size page size.
     * @param sort sort expression.
     * @return a paged envelope of the caller's {@link ReportDto}.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List my reports")
    public ApiResponse<java.util.List<ReportDto>> listMine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<ReportDto> result = reportService.listMyReports(CurrentUser.requirePublicId(), pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches one of the caller's own reports (US-3.2).
     *
     * @param reportId the report's public id.
     * @return an envelope with the owner-view {@link ReportDto}.
     */
    @GetMapping("/{reportId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get one of my reports")
    public ApiResponse<ReportDto> getMine(@PathVariable UUID reportId) {
        return responses.ok(reportService.getMyReport(CurrentUser.requirePublicId(), reportId));
    }

    /**
     * Returns the full timeline of one of the caller's own reports (US-3.2).
     *
     * @param reportId the report's public id.
     * @param page     zero-based page index.
     * @param size     page size.
     * @param sort     sort expression.
     * @return a paged envelope of {@link CaseEventDto}.
     */
    @GetMapping("/{reportId}/timeline")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get my report's timeline")
    public ApiResponse<java.util.List<CaseEventDto>> timeline(
            @PathVariable UUID reportId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<CaseEventDto> result = reportService.getMyReportTimeline(
                CurrentUser.requirePublicId(), reportId, pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Adds a comment/extra info to one of the caller's own reports (US-3.2).
     *
     * @param reportId the report's public id.
     * @param request  the validated comment.
     * @return {@code 201} + the appended {@link CaseEventDto}.
     */
    @PostMapping("/{reportId}/comments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Add info/comment to my report")
    public ResponseEntity<ApiResponse<CaseEventDto>> addComment(
            @PathVariable UUID reportId, @Valid @RequestBody AddCommentDto request) {
        CaseEventDto event = reportService.addComment(
                CurrentUser.requirePublicId(), reportId, request.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(event));
    }

    /**
     * Confirms or disputes the resolution of one of the caller's own reports (US-3.5, UC-D11/12/13).
     *
     * @param reportId the report's public id.
     * @param request  the confirm/dispute decision.
     * @return {@code 200} + the updated owner-view {@link ReportDto}.
     */
    @PostMapping("/{reportId}/confirmation")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Confirm or dispute a resolution")
    public ApiResponse<ReportDto> confirm(@PathVariable UUID reportId,
                                          @Valid @RequestBody ConfirmResolutionDto request) {
        return responses.ok(reportService.confirmResolution(
                CurrentUser.requirePublicId(), reportId, request.confirmed(), request.reason()));
    }
}

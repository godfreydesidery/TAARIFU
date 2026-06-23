package com.taarifu.admin.api.controller;

import com.taarifu.admin.api.dto.AdminStatsDto;
import com.taarifu.admin.application.service.ReportsAdminService;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.api.dto.PageMeta;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.reporting.api.dto.AdminReportDetail;
import com.taarifu.reporting.api.dto.AdminReportPage;
import com.taarifu.reporting.api.dto.AdminReportQuery;
import com.taarifu.reporting.api.dto.AdminReportSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin console's <b>reports queue, case detail, and overview stats</b> surface (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: a thin HTTP layer over {@link ReportsAdminService} that returns the back-office views
 * of the reporting domain — the owner-grade paged queue, one case's staff detail (including the internal
 * timeline), and the aggregate counts the dashboard header needs. It holds no business logic and no
 * {@code @Transactional} (ARCHITECTURE §3.3); all data is sourced through the reporting module's published
 * {@link com.taarifu.reporting.api.ReportQueryApi} (ADR-0013 §1 — no reach into reporting internals).</p>
 *
 * <p><b>Authorization (deny-by-default, ARCHITECTURE §6.2; PRD §7.1, §18):</b>
 * {@code hasAnyRole('ADMIN','MODERATOR')} on every method. These are back-office case-management powers; a
 * citizen/responder token is forbidden (403) and an anonymous request is unauthenticated (401) — the
 * security-gate test fails closed if the annotation is removed.</p>
 *
 * <p><b>Staff second factor (MFA):</b> the MFA gate is upstream — a {@code ROLE_ADMIN}/{@code ROLE_MODERATOR}
 * access token is only minted after the TOTP step (AUTH-DESIGN §14.1), so reaching these handlers already
 * implies MFA was satisfied (identical to the moderation queue / responder-admin surfaces). No extra
 * per-method MFA expression is needed; requiring the staff role IS the MFA-gated path.</p>
 *
 * <p><b>Privacy:</b> the queue and detail are served as the reporting module's PII-minimised projections —
 * no reporter identity, no precise geo-point; the internal timeline reaches only these {@code ADMIN}/
 * {@code MODERATOR} handlers (PRD §18 / PDPA, D-Q1).</p>
 */
@RestController
@RequestMapping(path = "/admin")
@Tag(name = "Admin Reports", description = "Back-office reports queue, case detail, and overview stats.")
public class AdminReportsController {

    private final ReportsAdminService reportsAdmin;
    private final PageRequestFactory pageRequests;
    private final ResponseFactory responses;

    /**
     * @param reportsAdmin the reports admin read service.
     * @param pageRequests reuses the kernel's page-size cap/defaults (DoS/data-budget guard, PRD §15).
     * @param responses    envelope builder.
     */
    public AdminReportsController(ReportsAdminService reportsAdmin,
                                  PageRequestFactory pageRequests,
                                  ResponseFactory responses) {
        this.reportsAdmin = reportsAdmin;
        this.pageRequests = pageRequests;
        this.responses = responses;
    }

    /**
     * Lists the owner-grade report queue, filtered and paginated (PII-minimised rows).
     *
     * @param status      optional lifecycle-status filter (e.g. {@code NEW}, {@code IN_PROGRESS}); an
     *                    unknown value yields an empty page rather than an error.
     * @param categoryId  optional issue-category {@code publicId} filter.
     * @param areaId      optional ward (Kata) {@code publicId} filter (the admin's "area" dimension).
     * @param slaBreached optional SLA-breach filter ({@code true}=only breached, {@code false}=only not,
     *                    absent=any).
     * @param page        zero-based page index (defaults 0).
     * @param size        page size (capped at the kernel's {@code MAX_SIZE}).
     * @return {@code 200} + a paged list of {@link AdminReportSummary} with pagination {@code meta}.
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    @Operation(summary = "List the owner-grade report queue (admin/moderator)",
            description = "Filter by status/category/area/slaBreached; PII-minimised rows (PRD §18).")
    public ApiResponse<List<AdminReportSummary>> listReports(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) Boolean slaBreached,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        // Reuse the kernel page cap (the port also clamps as defence-in-depth). Sort is fixed server-side
        // (newest-filed first) so the client cannot pass an arbitrary sort property into the port query.
        Pageable pageable = pageRequests.of(page, size, null);
        AdminReportQuery filter = new AdminReportQuery(status, categoryId, areaId, slaBreached);
        AdminReportPage result =
                reportsAdmin.listReports(filter, pageable.getPageNumber(), pageable.getPageSize());
        PageMeta meta = new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages());
        return responses.paged(result.content(), meta);
    }

    /**
     * Loads one case's staff detail — lifecycle/SLA state, the assigned-responder reference, and the full
     * timeline (public + internal responder notes, US-3.4).
     *
     * @param id the report's public id.
     * @return {@code 200} + the {@link AdminReportDetail}.
     */
    @GetMapping("/reports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    @Operation(summary = "Get a case's staff detail incl. internal timeline (admin/moderator)")
    public ApiResponse<AdminReportDetail> caseDetail(@PathVariable UUID id) {
        return responses.ok(reportsAdmin.caseDetail(id));
    }

    /**
     * Returns the overview aggregate counts the dashboard header binds to (reports by status, open cases,
     * SLA-breached cases, verification-queue depth, flags pending).
     *
     * @return {@code 200} + the {@link AdminStatsDto}.
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
    @Operation(summary = "Aggregate dashboard counts (admin/moderator)",
            description = "Report counts via reporting's api port; queue depths via published stats SPI.")
    public ApiResponse<AdminStatsDto> stats() {
        return responses.ok(reportsAdmin.stats());
    }
}

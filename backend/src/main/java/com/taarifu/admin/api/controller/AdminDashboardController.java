package com.taarifu.admin.api.controller;

import com.taarifu.admin.api.dto.DashboardStatsDto;
import com.taarifu.admin.application.service.DashboardService;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform <b>dashboard</b> aggregation surface for the admin console (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: a thin HTTP layer that returns the cross-module overview the Angular admin renders —
 * reports by status, open cases, reps onboarded, verification-queue depth, users by tier, flags pending —
 * by delegating to {@link DashboardService} (which aggregates each module's published
 * {@link com.taarifu.admin.api.spi.ModuleStatsProvider}). No business logic, no {@code @Transactional}
 * (ARCHITECTURE §3.3).</p>
 *
 * <p><b>Authorization (deny-by-default, ARCHITECTURE §6.2):</b> {@code hasAnyRole('ADMIN','ROOT')} — the
 * platform overview is a back-office power. Spring maps the JWT roles {@code ADMIN}/{@code ROOT} to
 * authorities {@code ROLE_ADMIN}/{@code ROOT}. A citizen/responder token is forbidden (403), and an
 * anonymous request is unauthenticated (401) — the security-gate test fails closed if this annotation is
 * removed (PRD §7.1).</p>
 */
@RestController
@RequestMapping(path = "/admin/dashboard")
@Tag(name = "Admin Dashboard", description = "Cross-module platform stats for the back-office console.")
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final ResponseFactory responses;

    /**
     * @param dashboardService cross-module stats aggregator.
     * @param responses        envelope builder.
     */
    public AdminDashboardController(DashboardService dashboardService, ResponseFactory responses) {
        this.dashboardService = dashboardService;
        this.responses = responses;
    }

    /**
     * Returns the aggregated platform dashboard.
     *
     * @return {@code 200} + every contributing module's section of counts (degraded sections are flagged,
     *         never omitted).
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Aggregated platform dashboard stats",
            description = "Counts aggregated across modules via their published stats providers (ADR-0013).")
    public ApiResponse<DashboardStatsDto> stats() {
        return responses.ok(dashboardService.compute());
    }
}

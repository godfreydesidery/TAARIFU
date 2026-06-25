package com.taarifu.admin.api.controller;

import com.taarifu.admin.application.service.AnalyticsAdminService;
import com.taarifu.analytics.api.dto.DashboardOverviewDto;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The admin console's <b>composed analytics dashboard</b> surface (M14/M15; US-14.1/US-15.1; UC-H06;
 * ADR-0020 §3).
 *
 * <p>Responsibility: a thin HTTP layer over {@link AnalyticsAdminService} that returns the dashboard's
 * headline-KPI overview in <b>one</b> call (so a low-bandwidth admin client need not fire the eight
 * per-tile endpoints separately — PRD §15). All data is sourced through the analytics module's published
 * {@link com.taarifu.analytics.api.AnalyticsQueryApi} read port (ADR-0013 §1 — no reach into analytics
 * internals). No business logic, no {@code @Transactional} (ARCHITECTURE §3.3).</p>
 *
 * <p>This admin-owned overview endpoint ({@code GET /admin/analytics/overview}) <b>complements</b> the
 * per-tile detail endpoints owned by the analytics module's {@code AnalyticsDashboardController} (also under
 * {@code /admin/analytics/*}, e.g. {@code /reports/volume}, {@code /verification/funnel}). The sub-paths are
 * distinct, so the two controllers co-exist under one path namespace without collision; each module owns its
 * own surface (ADR-0020 §3 consequence).</p>
 *
 * <p><b>Authorization (deny-by-default, ARCHITECTURE §6.2; PRD §7.1, §18, US-15.1):</b>
 * {@code hasAnyRole('ADMIN','ROOT','MODERATOR','RESPONDER_ADMIN','REPRESENTATIVE')} — the same Admin/authority
 * read policy as the analytics tile endpoints. A citizen token is forbidden (403) and an anonymous request is
 * unauthenticated (401); the security-gate test fails closed if the annotation is removed.</p>
 *
 * <p><b>No PII (Appendix E.4):</b> the {@link DashboardOverviewDto} is aggregate counts/percentiles only —
 * nothing here resolves to a person, a precise location, or any free-text body.</p>
 */
@RestController
@RequestMapping(path = "/admin/analytics")
@Tag(name = "Admin Analytics", description = "Composed admin dashboard overview (headline KPIs in one call).")
public class AdminAnalyticsController {

    /**
     * The Admin/authority read roles permitted to read the dashboard overview (US-15.1). Centralised so the
     * policy matches the analytics tile endpoints exactly (DRY); Spring maps JWT role {@code X} to authority
     * {@code ROLE_X}, matching {@code hasAnyRole(...)}.
     */
    private static final String DASHBOARD_ROLES =
            "hasAnyRole('ADMIN','ROOT','MODERATOR','RESPONDER_ADMIN','REPRESENTATIVE')";

    private final AnalyticsAdminService analyticsAdmin;
    private final ResponseFactory responses;

    /**
     * @param analyticsAdmin the analytics dashboard composition service.
     * @param responses      envelope builder.
     */
    public AdminAnalyticsController(AnalyticsAdminService analyticsAdmin, ResponseFactory responses) {
        this.analyticsAdmin = analyticsAdmin;
        this.responses = responses;
    }

    /**
     * Returns the composed dashboard overview (headline KPIs) the admin console binds on load.
     *
     * @param from      optional inclusive window start (ISO-8601 UTC); defaults to 30 days before {@code to}.
     * @param to        optional exclusive window end (ISO-8601 UTC); defaults to now.
     * @param geoAreaId optional area filter applied to the area-scopable tiles.
     * @return {@code 200} + an envelope carrying the {@link DashboardOverviewDto}.
     */
    @GetMapping("/overview")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Composed admin dashboard overview (headline KPIs in one call)",
            description = "Reports volume + TTFR/TTR + verification funnel + SLA breaches + channel mix + "
                    + "moderation actions over one window; sourced via analytics' published read port.")
    public ApiResponse<DashboardOverviewDto> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId) {
        return responses.ok(analyticsAdmin.overview(from, to, geoAreaId));
    }
}

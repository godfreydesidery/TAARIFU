package com.taarifu.admin.application.service;

import com.taarifu.analytics.api.AnalyticsQueryApi;
import com.taarifu.analytics.api.dto.DashboardOverviewDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The admin console's <b>analytics dashboard composition</b> service (M14/M15; US-14.1/US-15.1; UC-H06;
 * ADR-0020 §3).
 *
 * <p>Responsibility: serve the admin dashboard's headline-KPI overview by composing <b>only</b> the
 * analytics module's published {@link AnalyticsQueryApi} read port — never importing analytics' internals
 * ({@code application}/{@code domain}), which the {@code ModuleBoundaryTest} forbids (ADR-0013 §1,
 * ARCHITECTURE §3.2). This mirrors how {@link ReportsAdminService} consumes {@code reporting.api.ReportQueryApi}:
 * a sanctioned {@code admin → analytics} {@code api} dependency, injected as the interface.</p>
 *
 * <p>No {@code @Transactional} here — the analytics port manages its own read-only transaction over the
 * analytics read model; this service only orchestrates (counts/percentiles cross the boundary, never PII —
 * PRD §18 / Appendix E.4). It is the unit-tested seam; the controller is a thin HTTP wrapper.</p>
 */
@Service
public class AnalyticsAdminService {

    private final AnalyticsQueryApi analyticsQueryApi;

    /**
     * @param analyticsQueryApi analytics' published dashboard read port — injected as the interface, never
     *                          the impl (ADR-0013 §1).
     */
    public AnalyticsAdminService(AnalyticsQueryApi analyticsQueryApi) {
        this.analyticsQueryApi = analyticsQueryApi;
    }

    /**
     * Returns the composed dashboard overview (headline KPIs) for a window, optionally scoped to an area.
     *
     * @param from      optional inclusive window start (UTC); {@code null} defaults to 30 days before {@code to}.
     * @param to        optional exclusive window end (UTC); {@code null} defaults to now.
     * @param geoAreaId optional area filter ({@code null} = all).
     * @return the composed {@link DashboardOverviewDto}.
     */
    public DashboardOverviewDto overview(Instant from, Instant to, UUID geoAreaId) {
        return analyticsQueryApi.overview(from, to, geoAreaId);
    }
}

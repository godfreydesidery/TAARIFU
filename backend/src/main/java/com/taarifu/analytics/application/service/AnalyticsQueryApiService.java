package com.taarifu.analytics.application.service;

import com.taarifu.analytics.api.AnalyticsQueryApi;
import com.taarifu.analytics.api.dto.DashboardOverviewDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Implementation of the published {@link AnalyticsQueryApi} read port (ADR-0013 §1; ADR-0020 §2; M15).
 *
 * <p>Responsibility: the adapter that exposes the composed dashboard overview across the module boundary.
 * It owns the read transaction and delegates straight to {@link AnalyticsQueryService#overview} — it adds
 * <b>no new query logic</b> (DRY), it only realises the published contract so a sibling module (admin) can
 * depend on the interface, never on the internal {@code application.service} (which the
 * {@code ModuleBoundaryTest} forbids importing across modules).</p>
 *
 * <p>WHY a separate impl rather than annotating {@code AnalyticsQueryService} as the port: the internal
 * query service serves the analytics module's own controller with a broad surface (many tile methods); the
 * published port is deliberately narrow (one composed overview, ISP). Keeping them distinct lets the public
 * contract evolve independently of the internal service shape.</p>
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsQueryApiService implements AnalyticsQueryApi {

    private final AnalyticsQueryService queryService;

    /**
     * @param queryService the internal aggregation service this port delegates to.
     */
    public AnalyticsQueryApiService(AnalyticsQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link AnalyticsQueryService#overview(Instant, Instant, UUID)}; window defaulting and
     * area scoping are handled there.</p>
     */
    @Override
    public DashboardOverviewDto overview(Instant from, Instant to, UUID geoAreaId) {
        return queryService.overview(from, to, geoAreaId);
    }
}

package com.taarifu.analytics.api;

import com.taarifu.analytics.api.dto.DashboardOverviewDto;

import java.time.Instant;
import java.util.UUID;

/**
 * The analytics module's <b>published, in-process read port</b> for the composed dashboard overview
 * (ADR-0013 §1 — the {@code *QueryApi} shape; the same house pattern as
 * {@link com.taarifu.tokens.api.TokenLedgerApi} and {@code reporting.api.ReportQueryApi}; M15, ADR-0020 §2).
 *
 * <p>Responsibility: the single synchronous contract the <b>admin</b> module depends on to compose the
 * operational dashboard, <b>without importing analytics' internals</b> ({@code application}/{@code domain})
 * — forbidden by ARCHITECTURE §3.2 and the {@code ModuleBoundaryTest}. The admin module injects this
 * interface (never the impl); the impl lives in analytics' {@code application.service} and delegates to the
 * existing aggregation service (no duplicated query logic — DRY). Only public DTOs/{@code UUID}s/{@link Instant}s
 * cross the boundary — never an entity, repository, or {@code domain} type.</p>
 *
 * <p>WHY a read port (and not exposing {@code AnalyticsQueryService} directly): {@code AnalyticsQueryService}
 * is an internal {@code application.service}; a sibling module reaching it would breach the boundary the
 * {@code ModuleBoundaryTest} enforces. The published {@code api} port is the sanctioned seam — and it keeps
 * extract-to-service open (the interface can later become a remote client, callers unchanged, ADR-0013).</p>
 *
 * <p><b>🔒 No PII (Appendix E.4, binding — §18/PDPA):</b> the returned {@link DashboardOverviewDto} is
 * counts/percentiles over the PII-free {@code analytics_event} facts; there is no method or field through
 * which a name, phone, national/voter ID, free text, or precise location could cross this boundary.</p>
 */
public interface AnalyticsQueryApi {

    /**
     * Returns the composed dashboard overview (headline KPIs) for a window, optionally scoped to an area.
     *
     * @param from      optional inclusive window start (UTC); {@code null} defaults to 30 days before {@code to}.
     * @param to        optional exclusive window end (UTC); {@code null} defaults to now.
     * @param geoAreaId optional area filter applied to the area-scopable tiles ({@code null} = all).
     * @return the composed {@link DashboardOverviewDto} (never {@code null}; empty facts yield zeroed tiles).
     */
    DashboardOverviewDto overview(Instant from, Instant to, UUID geoAreaId);
}

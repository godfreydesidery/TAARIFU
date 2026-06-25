package com.taarifu.analytics.api.controller;

import com.taarifu.analytics.api.dto.BreakdownDto;
import com.taarifu.analytics.api.dto.FunnelDto;
import com.taarifu.analytics.api.dto.LatencyStatsDto;
import com.taarifu.analytics.api.dto.TimeBucket;
import com.taarifu.analytics.api.dto.TimeSeriesDto;
import com.taarifu.analytics.api.dto.VolumeReportDto;
import com.taarifu.analytics.application.service.AnalyticsQueryService;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
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
 * Admin/authority read-only REST surface for the analytics dashboards (PRD US-15.1; §3.3 KPIs;
 * Appendix C; M15; ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the thin HTTP layer for the operational dashboards — reports volume + TTFR/TTR by
 * area/category/time, SLA-breach counts, the T0→T3 verification funnel, channel mix, engagement counts,
 * and moderation actions — delegating to {@link AnalyticsQueryService} and wrapping every result in the
 * single {@link ApiResponse} envelope. No business logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization (deny-by-default — ARCHITECTURE.md §6.2):</b> these endpoints are <b>not</b> in the
 * public reference-data allow-list, so they require authentication, and every method is
 * {@code @PreAuthorize}-gated to the platform/authority read roles
 * ({@code ADMIN}/{@code ROOT}/{@code MODERATOR}/{@code RESPONDER_ADMIN}/{@code REPRESENTATIVE}) per US-15.1
 * "Admin/Authority dashboards". A citizen token reaching one of these handlers is denied at the method
 * layer. WHY method-level (not URL-only): admin surfaces must never be merely "authenticated-only" — the
 * legacy gap (PRD §7.1).</p>
 *
 * <p><b>No PII surfaced (Appendix E.4):</b> every payload is aggregate counts/percentiles over pseudonymous
 * facts; nothing here resolves to a person, a precise location, or any report/comment body.</p>
 */
@RestController
@RequestMapping(path = "/admin/analytics")
@Tag(name = "Analytics", description = "Admin/authority operational dashboards (KPIs, funnels, SLA, channel mix).")
public class AnalyticsDashboardController {

    /**
     * The roles permitted to read the dashboards (US-15.1 Admin/Authority). Centralised as a constant so
     * every handler shares one policy and a change is one edit (DRY). Spring maps JWT role {@code X} to
     * authority {@code ROLE_X}, matching {@code hasAnyRole(...)}.
     */
    private static final String DASHBOARD_ROLES =
            "hasAnyRole('ADMIN','ROOT','MODERATOR','RESPONDER_ADMIN','REPRESENTATIVE')";

    private final AnalyticsQueryService queryService;
    private final ResponseFactory responses;

    /**
     * @param queryService dashboard aggregation reads.
     * @param responses    envelope builder.
     */
    public AnalyticsDashboardController(AnalyticsQueryService queryService, ResponseFactory responses) {
        this.queryService = queryService;
        this.responses = responses;
    }

    /**
     * Reports-volume dashboard: total reports filed plus breakdowns by category and area.
     *
     * @param from       optional inclusive window start (ISO-8601 UTC); defaults to 30 days before {@code to}.
     * @param to         optional exclusive window end (ISO-8601 UTC); defaults to now.
     * @param geoAreaId  optional area filter for the headline total.
     * @param categoryId optional category filter for the headline total.
     * @return an envelope carrying the {@link VolumeReportDto}.
     */
    @GetMapping("/reports/volume")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Reports volume by area/category/time")
    public ApiResponse<VolumeReportDto> reportsVolume(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId,
            @RequestParam(required = false) UUID categoryId) {
        return responses.ok(queryService.reportsVolume(from, to, geoAreaId, categoryId));
    }

    /**
     * Time-to-first-response (TTFR) distribution — median/p90 over {@code REPORT_FIRST_RESPONDED}.
     *
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter.
     * @param categoryId optional category filter.
     * @return an envelope carrying the {@link LatencyStatsDto} labelled {@code TTFR}.
     */
    @GetMapping("/reports/ttfr")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Time-to-first-response (TTFR) distribution (p50/p90)")
    public ApiResponse<LatencyStatsDto> ttfr(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId,
            @RequestParam(required = false) UUID categoryId) {
        return responses.ok(queryService.latency(
                AnalyticsEventType.REPORT_FIRST_RESPONDED, "TTFR", from, to, geoAreaId, categoryId));
    }

    /**
     * Time-to-resolution (TTR) distribution — median/p90 over {@code REPORT_RESOLVED}.
     *
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter.
     * @param categoryId optional category filter.
     * @return an envelope carrying the {@link LatencyStatsDto} labelled {@code TTR}.
     */
    @GetMapping("/reports/ttr")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Time-to-resolution (TTR) distribution (p50/p90)")
    public ApiResponse<LatencyStatsDto> ttr(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId,
            @RequestParam(required = false) UUID categoryId) {
        return responses.ok(queryService.latency(
                AnalyticsEventType.REPORT_RESOLVED, "TTR", from, to, geoAreaId, categoryId));
    }

    /**
     * SLA-breach counts split by breach type (TTFR vs TTR) — the SLA-breach heatmap cell when scoped by
     * area/category.
     *
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter for the heatmap cell.
     * @param categoryId optional category filter for the heatmap cell.
     * @return an envelope carrying a {@link BreakdownDto} over {@code BREACH_TYPE}.
     */
    @GetMapping("/reports/sla-breaches")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "SLA-breach counts by breach type (TTFR/TTR)")
    public ApiResponse<BreakdownDto> slaBreaches(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId,
            @RequestParam(required = false) UUID categoryId) {
        return responses.ok(queryService.slaBreaches(from, to, geoAreaId, categoryId));
    }

    /**
     * Reports-volume <b>trend</b>: filed-report counts per time bucket (day/week/month) — the volume-over-time
     * line chart (PRD §3.3 trends; Appendix C).
     *
     * @param bucket     optional time-bucket granularity ({@code DAY}/{@code WEEK}/{@code MONTH}); defaults to {@code DAY}.
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter.
     * @param categoryId optional category filter.
     * @return an envelope carrying the {@link TimeSeriesDto} labelled {@code REPORT_FILED}.
     */
    @GetMapping("/reports/trend")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Reports-volume trend per time bucket (day/week/month)")
    public ApiResponse<TimeSeriesDto> reportsTrend(
            @RequestParam(required = false) TimeBucket bucket,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId,
            @RequestParam(required = false) UUID categoryId) {
        return responses.ok(queryService.reportsTrend(bucket, from, to, geoAreaId, categoryId));
    }

    /**
     * SLA-breach <b>trend</b>: escalation-with-breach counts per time bucket — the SLA-breach-over-time
     * line chart used to spot deteriorating responsiveness (PRD §3.3; Appendix C "SLA-breach trend").
     *
     * @param bucket     optional time-bucket granularity ({@code DAY}/{@code WEEK}/{@code MONTH}); defaults to {@code DAY}.
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter.
     * @param categoryId optional category filter.
     * @return an envelope carrying the {@link TimeSeriesDto} labelled {@code SLA_BREACH}.
     */
    @GetMapping("/reports/sla-breach-trend")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "SLA-breach trend per time bucket (day/week/month)")
    public ApiResponse<TimeSeriesDto> slaBreachTrend(
            @RequestParam(required = false) TimeBucket bucket,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId,
            @RequestParam(required = false) UUID categoryId) {
        return responses.ok(queryService.slaBreachTrend(bucket, from, to, geoAreaId, categoryId));
    }

    /**
     * The verification funnel T0→T3 (signup → profile → verification-started → verified) with conversion.
     *
     * @param from      optional inclusive window start (UTC).
     * @param to        optional exclusive window end (UTC).
     * @param geoAreaId optional area filter.
     * @return an envelope carrying the {@link FunnelDto}.
     */
    @GetMapping("/verification/funnel")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Verification funnel T0→T3 with step conversion")
    public ApiResponse<FunnelDto> verificationFunnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID geoAreaId) {
        return responses.ok(queryService.verificationFunnel(from, to, geoAreaId));
    }

    /**
     * Channel mix for filed reports — distribution across APP/WEB/PWA/USSD/SMS (% feature-phone reach).
     *
     * @param from optional inclusive window start (UTC).
     * @param to   optional exclusive window end (UTC).
     * @return an envelope carrying a {@link BreakdownDto} over {@code CHANNEL}.
     */
    @GetMapping("/reports/channel-mix")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Channel mix for filed reports (USSD/SMS/app/web)")
    public ApiResponse<BreakdownDto> reportChannelMix(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return responses.ok(queryService.channelMix(AnalyticsEventType.REPORT_FILED, from, to));
    }

    /**
     * Channel mix for sessions — distribution across channels (% sessions via USSD/SMS, §3.3 Reach).
     *
     * @param from optional inclusive window start (UTC).
     * @param to   optional exclusive window end (UTC).
     * @return an envelope carrying a {@link BreakdownDto} over {@code CHANNEL}.
     */
    @GetMapping("/sessions/channel-mix")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Channel mix for sessions (% via USSD/SMS)")
    public ApiResponse<BreakdownDto> sessionChannelMix(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return responses.ok(queryService.channelMix(AnalyticsEventType.SESSION_STARTED, from, to));
    }

    /**
     * Engagement counts — signatures, survey responses, questions asked/answered, ratings, follows.
     *
     * @param from optional inclusive window start (UTC).
     * @param to   optional exclusive window end (UTC).
     * @return an envelope carrying a {@link BreakdownDto} over {@code ENGAGEMENT_TYPE}.
     */
    @GetMapping("/engagement/counts")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Engagement counts (signatures, poll responses, Q&A, ratings, follows)")
    public ApiResponse<BreakdownDto> engagementCounts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return responses.ok(queryService.engagementCounts(from, to));
    }

    /**
     * Moderation actions split by outcome ({@code APPROVE/HIDE/REMOVE/WARN/SUSPEND/VERIFY}).
     *
     * @param from optional inclusive window start (UTC).
     * @param to   optional exclusive window end (UTC).
     * @return an envelope carrying a {@link BreakdownDto} over {@code OUTCOME}.
     */
    @GetMapping("/moderation/actions")
    @PreAuthorize(DASHBOARD_ROLES)
    @Operation(summary = "Moderation actions by outcome")
    public ApiResponse<BreakdownDto> moderationActions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return responses.ok(queryService.moderationActions(from, to));
    }
}

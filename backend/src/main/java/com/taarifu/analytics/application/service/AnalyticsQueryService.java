package com.taarifu.analytics.application.service;

import com.taarifu.analytics.api.dto.BreakdownDto;
import com.taarifu.analytics.api.dto.DashboardOverviewDto;
import com.taarifu.analytics.api.dto.FunnelDto;
import com.taarifu.analytics.api.dto.LatencyStatsDto;
import com.taarifu.analytics.api.dto.MetricPointDto;
import com.taarifu.analytics.api.dto.TimeBucket;
import com.taarifu.analytics.api.dto.TimeSeriesDto;
import com.taarifu.analytics.api.dto.VolumeReportDto;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.repository.AnalyticsEventRepository;
import com.taarifu.analytics.domain.repository.projection.CountByBucketProjection;
import com.taarifu.analytics.domain.repository.projection.CountByKeyProjection;
import com.taarifu.analytics.domain.repository.projection.LatencyStatsProjection;
import com.taarifu.common.domain.port.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only application service powering the analytics dashboards (PRD §3.3 KPIs; Appendix C; US-15.1;
 * M15; ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: orchestrates every dashboard read — reports volume + TTFR/TTR by area/category/time,
 * SLA-breach counts, the T0→T3 verification funnel, channel mix, engagement counts, and moderation actions
 * — by aggregating over the {@code analytics_event} table and mapping projections into the public DTOs. It
 * owns the read transaction and the <b>window defaulting</b> (a {@code null} window resolves to the last
 * 30 days), so every endpoint behaves identically (DRY). It never reads live from a sibling module — the
 * analytics read model is the source for these numbers (ADR-0013; Appendix E pipeline).</p>
 *
 * <p>WHY a service (not query logic in the controller): controllers stay thin (CLAUDE.md §8); the window
 * resolution, projection→DTO mapping, and funnel-conversion arithmetic live here, testable without the web
 * layer. The {@link ClockPort} makes "now" injectable so window defaulting is deterministic in tests.</p>
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsQueryService {

    /** Default look-back window when the caller supplies no {@code from} (30 days). */
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final AnalyticsEventRepository events;
    private final ClockPort clock;

    /**
     * @param events the analytics aggregation repository.
     * @param clock  injectable "now" for window defaulting (testability).
     */
    public AnalyticsQueryService(AnalyticsEventRepository events, ClockPort clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Reports-volume dashboard: total {@code REPORT_FILED} plus breakdowns by category and area.
     *
     * @param from       optional inclusive window start (UTC); defaults to 30 days before {@code to}.
     * @param to         optional exclusive window end (UTC); defaults to now.
     * @param geoAreaId  optional area filter for the headline total ({@code null} = all).
     * @param categoryId optional category filter for the headline total ({@code null} = all).
     * @return the {@link VolumeReportDto}.
     */
    public VolumeReportDto reportsVolume(Instant from, Instant to, UUID geoAreaId, UUID categoryId) {
        Window w = resolveWindow(from, to);
        long total = events.countByType(AnalyticsEventType.REPORT_FILED, w.from(), w.to(), geoAreaId, categoryId);
        List<MetricPointDto> byCategory = toPoints(
                events.countByTypeGroupedByCategory(AnalyticsEventType.REPORT_FILED, w.from(), w.to()));
        List<MetricPointDto> byArea = toPoints(
                events.countByTypeGroupedByArea(AnalyticsEventType.REPORT_FILED, w.from(), w.to()));
        return new VolumeReportDto(w.from(), w.to(), total, byCategory, byArea);
    }

    /**
     * Latency distribution (count + p50/p90/avg) for a latency-bearing event type — TTFR, TTR, or
     * answer-latency.
     *
     * @param eventType  one of {@code REPORT_FIRST_RESPONDED} (TTFR), {@code REPORT_RESOLVED} (TTR), or
     *                   {@code QUESTION_ANSWERED} (answer latency).
     * @param metricLabel a label for the metric to echo back (e.g. {@code "TTFR"}).
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter ({@code null} = all).
     * @param categoryId optional category filter ({@code null} = all).
     * @return the {@link LatencyStatsDto}.
     */
    public LatencyStatsDto latency(AnalyticsEventType eventType, String metricLabel, Instant from, Instant to,
                                   UUID geoAreaId, UUID categoryId) {
        Window w = resolveWindow(from, to);
        LatencyStatsProjection stats =
                events.latencyStats(eventType.name(), w.from(), w.to(), geoAreaId, categoryId);
        long count = stats != null ? stats.getSampleCount() : 0L;
        Double p50 = stats != null ? stats.getP50Seconds() : null;
        Double p90 = stats != null ? stats.getP90Seconds() : null;
        Double avg = stats != null ? stats.getAvgSeconds() : null;
        return new LatencyStatsDto(metricLabel, w.from(), w.to(), count, p50, p90, avg);
    }

    /**
     * SLA-breach counts split by breach type (TTFR vs TTR) over {@code REPORT_ESCALATED} — the SLA-breach
     * heatmap cell when scoped by area/category.
     *
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter for the heatmap cell ({@code null} = all).
     * @param categoryId optional category filter for the heatmap cell ({@code null} = all).
     * @return a {@link BreakdownDto} over {@code BREACH_TYPE}.
     */
    public BreakdownDto slaBreaches(Instant from, Instant to, UUID geoAreaId, UUID categoryId) {
        Window w = resolveWindow(from, to);
        List<MetricPointDto> points =
                toPoints(events.countSlaBreachesByType(w.from(), w.to(), geoAreaId, categoryId));
        return breakdown("BREACH_TYPE", w, points);
    }

    /**
     * The verification funnel T0→T3:
     * {@code ACCOUNT_SIGNED_UP} → {@code PROFILE_COMPLETED} → {@code IDENTITY_VERIFICATION_STARTED} →
     * {@code IDENTITY_VERIFIED}, with conversion from the top step.
     *
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter ({@code null} = all).
     * @return the {@link FunnelDto}.
     */
    public FunnelDto verificationFunnel(Instant from, Instant to, UUID geoAreaId) {
        Window w = resolveWindow(from, to);
        List<AnalyticsEventType> ordered = List.of(
                AnalyticsEventType.ACCOUNT_SIGNED_UP,
                AnalyticsEventType.PROFILE_COMPLETED,
                AnalyticsEventType.IDENTITY_VERIFICATION_STARTED,
                AnalyticsEventType.IDENTITY_VERIFIED);
        Map<String, Long> counts = events.countGroupedByType(ordered, w.from(), w.to(), geoAreaId, null)
                .stream()
                .collect(Collectors.toMap(CountByKeyProjection::getKey, CountByKeyProjection::getCount));

        long top = counts.getOrDefault(ordered.get(0).name(), 0L);
        List<FunnelDto.Step> steps = ordered.stream()
                .map(type -> {
                    long c = counts.getOrDefault(type.name(), 0L);
                    double conv = top == 0 ? 0.0 : (double) c / (double) top;
                    return new FunnelDto.Step(type.name(), c, conv);
                })
                .toList();
        return new FunnelDto("VERIFICATION_T0_T3", w.from(), w.to(), steps);
    }

    /**
     * Channel mix for an event type — the distribution across APP/WEB/PWA/USSD/SMS/... (% feature-phone reach).
     *
     * @param eventType the event type to split by channel (e.g. {@code SESSION_STARTED} or {@code REPORT_FILED}).
     * @param from      optional inclusive window start (UTC).
     * @param to        optional exclusive window end (UTC).
     * @return a {@link BreakdownDto} over {@code CHANNEL}.
     */
    public BreakdownDto channelMix(AnalyticsEventType eventType, Instant from, Instant to) {
        Window w = resolveWindow(from, to);
        List<MetricPointDto> points =
                toPoints(events.countByTypeGroupedByChannel(eventType, w.from(), w.to()));
        return breakdown("CHANNEL", w, points);
    }

    /**
     * Engagement counts — petition signatures, survey responses, questions asked/answered, ratings, and
     * follows — as one labelled breakdown (Appendix C "engagement (signatures, poll responses, follows)").
     *
     * @param from optional inclusive window start (UTC).
     * @param to   optional exclusive window end (UTC).
     * @return a {@link BreakdownDto} over {@code ENGAGEMENT_TYPE} where each point is an engagement event count.
     */
    public BreakdownDto engagementCounts(Instant from, Instant to) {
        Window w = resolveWindow(from, to);
        List<AnalyticsEventType> engagementTypes = List.of(
                AnalyticsEventType.PETITION_SIGNED,
                AnalyticsEventType.SURVEY_RESPONDED,
                AnalyticsEventType.QUESTION_ASKED,
                AnalyticsEventType.QUESTION_ANSWERED,
                AnalyticsEventType.REP_RATED,
                AnalyticsEventType.SUBSCRIPTION_CHANGED);
        List<MetricPointDto> points =
                toPoints(events.countGroupedByType(engagementTypes, w.from(), w.to(), null, null));
        return breakdown("ENGAGEMENT_TYPE", w, points);
    }

    /**
     * Moderation actions split by outcome ({@code APPROVE/HIDE/REMOVE/WARN/SUSPEND/VERIFY}) over
     * {@code MODERATION_ACTION_TAKEN} (Appendix C "moderation actions").
     *
     * @param from optional inclusive window start (UTC).
     * @param to   optional exclusive window end (UTC).
     * @return a {@link BreakdownDto} over {@code OUTCOME}.
     */
    public BreakdownDto moderationActions(Instant from, Instant to) {
        Window w = resolveWindow(from, to);
        List<MetricPointDto> points = toPoints(
                events.countByTypeGroupedByOutcome(AnalyticsEventType.MODERATION_ACTION_TAKEN, w.from(), w.to()));
        return breakdown("OUTCOME", w, points);
    }

    /**
     * Reports-volume <b>trend</b>: {@code REPORT_FILED} counts per time bucket (day/week/month) over the
     * window, optionally scoped to an area/category (PRD §3.3 trends; Appendix C).
     *
     * @param bucket     the time-bucket granularity ({@code null} defaults to {@link TimeBucket#DAY}).
     * @param from       optional inclusive window start (UTC); defaults to 30 days before {@code to}.
     * @param to         optional exclusive window end (UTC); defaults to now.
     * @param geoAreaId  optional area filter ({@code null} = all).
     * @param categoryId optional category filter ({@code null} = all).
     * @return the {@link TimeSeriesDto} labelled {@code REPORT_FILED}, chronologically ordered.
     */
    public TimeSeriesDto reportsTrend(TimeBucket bucket, Instant from, Instant to,
                                      UUID geoAreaId, UUID categoryId) {
        TimeBucket b = bucket != null ? bucket : TimeBucket.DAY;
        Window w = resolveWindow(from, to);
        List<TimeSeriesDto.Point> points = toSeries(events.countByTypeBucketed(
                AnalyticsEventType.REPORT_FILED.name(), b.truncField(), w.from(), w.to(), geoAreaId, categoryId));
        return new TimeSeriesDto(AnalyticsEventType.REPORT_FILED.name(), b, w.from(), w.to(), points);
    }

    /**
     * SLA-breach <b>trend</b>: {@code REPORT_ESCALATED}-with-breach counts per time bucket over the window,
     * optionally scoped to an area/category (PRD §3.3; Appendix C "SLA-breach trend").
     *
     * @param bucket     the time-bucket granularity ({@code null} defaults to {@link TimeBucket#DAY}).
     * @param from       optional inclusive window start (UTC).
     * @param to         optional exclusive window end (UTC).
     * @param geoAreaId  optional area filter ({@code null} = all).
     * @param categoryId optional category filter ({@code null} = all).
     * @return the {@link TimeSeriesDto} labelled {@code SLA_BREACH}, chronologically ordered.
     */
    public TimeSeriesDto slaBreachTrend(TimeBucket bucket, Instant from, Instant to,
                                        UUID geoAreaId, UUID categoryId) {
        TimeBucket b = bucket != null ? bucket : TimeBucket.DAY;
        Window w = resolveWindow(from, to);
        List<TimeSeriesDto.Point> points = toSeries(events.countSlaBreachesBucketed(
                b.truncField(), w.from(), w.to(), geoAreaId, categoryId));
        return new TimeSeriesDto("SLA_BREACH", b, w.from(), w.to(), points);
    }

    /**
     * Composes the dashboard <b>overview</b> — the headline KPIs in one payload — so a low-bandwidth client
     * fetches them in a single call (PRD §15; ADR-0020 §2/§3). Reuses the individual tile methods (DRY);
     * adds no new query logic. The {@code from}/{@code to} are resolved once and threaded through every tile
     * so the overview is internally consistent (one window).
     *
     * @param from      optional inclusive window start (UTC); defaults to 30 days before {@code to}.
     * @param to        optional exclusive window end (UTC); defaults to now.
     * @param geoAreaId optional area filter applied to the area-scopable tiles ({@code null} = all).
     * @return the composed {@link DashboardOverviewDto}.
     */
    public DashboardOverviewDto overview(Instant from, Instant to, UUID geoAreaId) {
        Window w = resolveWindow(from, to);
        return new DashboardOverviewDto(
                w.from(), w.to(),
                events.countByType(AnalyticsEventType.REPORT_FILED, w.from(), w.to(), geoAreaId, null),
                latency(AnalyticsEventType.REPORT_FIRST_RESPONDED, "TTFR", w.from(), w.to(), geoAreaId, null),
                latency(AnalyticsEventType.REPORT_RESOLVED, "TTR", w.from(), w.to(), geoAreaId, null),
                verificationFunnel(w.from(), w.to(), geoAreaId),
                slaBreaches(w.from(), w.to(), geoAreaId, null),
                channelMix(AnalyticsEventType.REPORT_FILED, w.from(), w.to()),
                moderationActions(w.from(), w.to()));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Maps the bucketed count projection into the public series point DTO (chronological order preserved). */
    private List<TimeSeriesDto.Point> toSeries(List<CountByBucketProjection> rows) {
        return rows.stream()
                .map(r -> new TimeSeriesDto.Point(r.getBucketStart(), r.getCount()))
                .toList();
    }

    /** Builds a {@link BreakdownDto} with the summed total over the points. */
    private BreakdownDto breakdown(String dimension, Window w, List<MetricPointDto> points) {
        long total = points.stream().mapToLong(MetricPointDto::count).sum();
        return new BreakdownDto(dimension, w.from(), w.to(), total, points);
    }

    /** Maps the shared count projection into the public point DTO. */
    private List<MetricPointDto> toPoints(List<CountByKeyProjection> rows) {
        return rows.stream().map(r -> new MetricPointDto(r.getKey(), r.getCount())).toList();
    }

    /**
     * Resolves an optional {@code from}/{@code to} into a concrete window: {@code to} defaults to now,
     * {@code from} defaults to {@code to - 30 days}. A caller-supplied {@code from > to} is normalised by
     * swapping, so a transposed range never returns an empty/negative window.
     */
    private Window resolveWindow(Instant from, Instant to) {
        Instant end = to != null ? to : clock.now();
        Instant start = from != null ? from : end.minus(DEFAULT_WINDOW);
        if (start.isAfter(end)) {
            return new Window(end, start);
        }
        return new Window(start, end);
    }

    /** A resolved, inclusive-start/exclusive-end time window. */
    private record Window(Instant from, Instant to) {
    }
}

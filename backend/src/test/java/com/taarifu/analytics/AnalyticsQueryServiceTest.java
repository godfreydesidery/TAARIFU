package com.taarifu.analytics;

import com.taarifu.analytics.api.dto.DashboardOverviewDto;
import com.taarifu.analytics.api.dto.FunnelDto;
import com.taarifu.analytics.api.dto.TimeBucket;
import com.taarifu.analytics.api.dto.TimeSeriesDto;
import com.taarifu.analytics.api.dto.VolumeReportDto;
import com.taarifu.analytics.application.service.AnalyticsQueryService;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.repository.AnalyticsEventRepository;
import com.taarifu.analytics.domain.repository.projection.CountByBucketProjection;
import com.taarifu.analytics.domain.repository.projection.CountByKeyProjection;
import com.taarifu.common.domain.port.ClockPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsQueryService} — the funnel-conversion arithmetic and window defaulting
 * that the dashboards depend on (M15; PRD §3.3 Verification; Appendix E.2 funnels).
 *
 * <p>Responsibility: proves, with a mocked repository (no DB, no Docker — runs in every CI lane), that
 * (a) the verification funnel computes step-to-top conversion correctly including the empty-top guard, and
 * (b) a {@code null} window resolves to the documented 30-day look-back via the injected {@link ClockPort}.
 * These are the pure-logic invariants worth pinning independently of the SQL aggregation (which the
 * Testcontainers IT proves end-to-end).</p>
 */
class AnalyticsQueryServiceTest {

    private final AnalyticsEventRepository events = mock(AnalyticsEventRepository.class);
    private final Instant fixedNow = Instant.parse("2026-06-23T12:00:00Z");
    private final ClockPort clock = () -> fixedNow;
    private final AnalyticsQueryService service = new AnalyticsQueryService(events, clock);

    /** A tiny inline projection so the mock can return (key, count) rows. */
    private static CountByKeyProjection point(String key, long count) {
        return new CountByKeyProjection() {
            @Override public String getKey() { return key; }
            @Override public long getCount() { return count; }
        };
    }

    /** A tiny inline projection so the mock can return (bucketStart, count) trend rows. */
    private static CountByBucketProjection bucket(Instant start, long count) {
        return new CountByBucketProjection() {
            @Override public Instant getBucketStart() { return start; }
            @Override public long getCount() { return count; }
        };
    }

    @Test
    void verificationFunnel_computesConversionFromTop() {
        // Signups 100 → profile 60 → verification-started 40 → verified 25.
        when(events.countGroupedByType(any(), any(), any(), any(), any())).thenReturn(List.of(
                point(AnalyticsEventType.ACCOUNT_SIGNED_UP.name(), 100),
                point(AnalyticsEventType.PROFILE_COMPLETED.name(), 60),
                point(AnalyticsEventType.IDENTITY_VERIFICATION_STARTED.name(), 40),
                point(AnalyticsEventType.IDENTITY_VERIFIED.name(), 25)));

        FunnelDto funnel = service.verificationFunnel(
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-23T00:00:00Z"), null);

        assertThat(funnel.name()).isEqualTo("VERIFICATION_T0_T3");
        assertThat(funnel.steps()).hasSize(4);
        // Order preserved top → bottom.
        assertThat(funnel.steps().get(0).step()).isEqualTo("ACCOUNT_SIGNED_UP");
        assertThat(funnel.steps().get(0).conversionFromTop()).isEqualTo(1.0);
        // 25 verified of 100 signups = 25% reaching T3 (the §3.3 ≥40% headline number).
        assertThat(funnel.steps().get(3).step()).isEqualTo("IDENTITY_VERIFIED");
        assertThat(funnel.steps().get(3).count()).isEqualTo(25);
        assertThat(funnel.steps().get(3).conversionFromTop()).isEqualTo(0.25);
    }

    @Test
    void verificationFunnel_emptyTop_yieldsZeroConversionNotDivideByZero() {
        // No signups in the window → every step's conversion must be 0.0, never NaN/Infinity.
        when(events.countGroupedByType(any(), any(), any(), any(), any())).thenReturn(List.of());

        FunnelDto funnel = service.verificationFunnel(null, null, null);

        assertThat(funnel.steps()).allSatisfy(s -> {
            assertThat(s.count()).isZero();
            assertThat(s.conversionFromTop()).isEqualTo(0.0);
        });
    }

    @Test
    void reportsVolume_nullWindow_defaultsTo30DaysBeforeNow() {
        when(events.countByType(eq(AnalyticsEventType.REPORT_FILED), any(), any(), any(), any()))
                .thenReturn(7L);
        when(events.countByTypeGroupedByCategory(any(), any(), any())).thenReturn(List.of());
        when(events.countByTypeGroupedByArea(any(), any(), any())).thenReturn(List.of());

        VolumeReportDto dto = service.reportsVolume(null, null, null, null);

        // Window: to=now, from=now-30d (the documented default).
        assertThat(dto.to()).isEqualTo(fixedNow);
        assertThat(dto.from()).isEqualTo(fixedNow.minus(Duration.ofDays(30)));
        assertThat(dto.total()).isEqualTo(7L);
    }

    @Test
    void reportsVolume_transposedWindow_isNormalisedBySwapping() {
        when(events.countByType(any(), any(), any(), any(), any())).thenReturn(0L);
        when(events.countByTypeGroupedByCategory(any(), any(), any())).thenReturn(List.of());
        when(events.countByTypeGroupedByArea(any(), any(), any())).thenReturn(List.of());
        Instant later = Instant.parse("2026-06-20T00:00:00Z");
        Instant earlier = Instant.parse("2026-06-01T00:00:00Z");

        // from > to passed in (transposed) → service swaps so from <= to.
        VolumeReportDto dto = service.reportsVolume(later, earlier, UUID.randomUUID(), null);

        assertThat(dto.from()).isEqualTo(earlier);
        assertThat(dto.to()).isEqualTo(later);
    }

    @Test
    void reportsTrend_nullBucket_defaultsToDay_andMapsPointsInOrder() {
        Instant d1 = Instant.parse("2026-06-01T00:00:00Z");
        Instant d2 = Instant.parse("2026-06-02T00:00:00Z");
        // Repository returns chronologically-ordered buckets; the service must preserve the order and the
        // DEFAULT bucket must be DAY when the caller passes null (the documented default — ADR-0020 §1).
        when(events.countByTypeBucketed(eq(AnalyticsEventType.REPORT_FILED.name()), eq("day"),
                any(), any(), any(), any()))
                .thenReturn(List.of(bucket(d1, 3), bucket(d2, 5)));

        TimeSeriesDto series = service.reportsTrend(null, null, null, null, null);

        assertThat(series.bucket()).isEqualTo(TimeBucket.DAY);
        assertThat(series.metric()).isEqualTo("REPORT_FILED");
        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(0).bucketStart()).isEqualTo(d1);
        assertThat(series.points().get(0).count()).isEqualTo(3);
        assertThat(series.points().get(1).count()).isEqualTo(5);
    }

    @Test
    void slaBreachTrend_usesChosenBucketTruncField() {
        // A MONTH request must pass the 'month' date_trunc literal to the repository (proves the enum→literal
        // mapping that keeps the native query injection-safe — ADR-0020 §1).
        when(events.countSlaBreachesBucketed(eq("month"), any(), any(), any(), any()))
                .thenReturn(List.of(bucket(Instant.parse("2026-06-01T00:00:00Z"), 2)));

        TimeSeriesDto series = service.slaBreachTrend(TimeBucket.MONTH, null, null, null, null);

        assertThat(series.bucket()).isEqualTo(TimeBucket.MONTH);
        assertThat(series.metric()).isEqualTo("SLA_BREACH");
        assertThat(series.points()).singleElement()
                .satisfies(p -> assertThat(p.count()).isEqualTo(2));
    }

    @Test
    void overview_composesEveryTileOverOneResolvedWindow() {
        // Stub each underlying aggregation; the overview must thread ONE resolved window into every tile and
        // surface each result (ADR-0020 §2). Null window → 30-day default via the injected clock.
        when(events.countByType(eq(AnalyticsEventType.REPORT_FILED), any(), any(), any(), any()))
                .thenReturn(42L);
        when(events.latencyStats(any(), any(), any(), any(), any())).thenReturn(null);
        when(events.countGroupedByType(any(), any(), any(), any(), any())).thenReturn(List.of(
                point(AnalyticsEventType.ACCOUNT_SIGNED_UP.name(), 10)));
        when(events.countSlaBreachesByType(any(), any(), any(), any())).thenReturn(List.of());
        when(events.countByTypeGroupedByChannel(any(), any(), any())).thenReturn(List.of());
        when(events.countByTypeGroupedByOutcome(any(), any(), any())).thenReturn(List.of());

        DashboardOverviewDto overview = service.overview(null, null, null);

        // One window threaded through every tile.
        assertThat(overview.to()).isEqualTo(fixedNow);
        assertThat(overview.from()).isEqualTo(fixedNow.minus(Duration.ofDays(30)));
        assertThat(overview.from()).isEqualTo(overview.verificationFunnel().from());
        assertThat(overview.to()).isEqualTo(overview.slaBreaches().to());
        // Headline + labelled tiles present.
        assertThat(overview.reportsVolumeTotal()).isEqualTo(42L);
        assertThat(overview.ttfr().metric()).isEqualTo("TTFR");
        assertThat(overview.ttr().metric()).isEqualTo("TTR");
        assertThat(overview.verificationFunnel().name()).isEqualTo("VERIFICATION_T0_T3");
    }
}

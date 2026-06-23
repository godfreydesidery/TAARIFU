package com.taarifu.analytics;

import com.taarifu.analytics.api.dto.FunnelDto;
import com.taarifu.analytics.api.dto.VolumeReportDto;
import com.taarifu.analytics.application.service.AnalyticsQueryService;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.repository.AnalyticsEventRepository;
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
}

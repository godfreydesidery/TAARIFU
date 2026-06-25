package com.taarifu.admin.application.service;

import com.taarifu.analytics.api.AnalyticsQueryApi;
import com.taarifu.analytics.api.dto.DashboardOverviewDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsAdminService} — the admin module's analytics composition seam
 * (M14/M15; ADR-0020 §3; ADR-0013 §1).
 *
 * <p>Responsibility: proves the admin overview is served <b>only</b> through the analytics module's
 * published {@link AnalyticsQueryApi} read port (the boundary contract — admin never reaches into analytics
 * internals) and that the window/area arguments are passed through unchanged. With a mocked port this runs
 * on every CI lane (no DB, no Docker). It is the unit-tested seam behind the thin controller.</p>
 */
class AnalyticsAdminServiceTest {

    private final AnalyticsQueryApi analyticsQueryApi = mock(AnalyticsQueryApi.class);
    private final AnalyticsAdminService service = new AnalyticsAdminService(analyticsQueryApi);

    @Test
    void overview_delegatesToPublishedPort_passingWindowAndAreaThrough() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z");
        UUID area = UUID.randomUUID();
        DashboardOverviewDto expected = new DashboardOverviewDto(
                from, to, 7L, null, null, null, null, null, null);
        when(analyticsQueryApi.overview(eq(from), eq(to), eq(area))).thenReturn(expected);

        DashboardOverviewDto actual = service.overview(from, to, area);

        // The admin service returns exactly what the analytics port produced (no re-computation in admin).
        assertThat(actual).isSameAs(expected);
        // And it composed ONLY through the published port (the boundary contract — ADR-0013 §1).
        verify(analyticsQueryApi).overview(from, to, area);
    }

    @Test
    void overview_passesNullsThroughForServiceSideDefaulting() {
        // Admin does no window defaulting itself — it delegates so analytics applies the documented 30-day
        // default. A null window/area must reach the port unchanged.
        when(analyticsQueryApi.overview(null, null, null))
                .thenReturn(new DashboardOverviewDto(null, null, 0L, null, null, null, null, null, null));

        service.overview(null, null, null);

        verify(analyticsQueryApi).overview(null, null, null);
    }
}

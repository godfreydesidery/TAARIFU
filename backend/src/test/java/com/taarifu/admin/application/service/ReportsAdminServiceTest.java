package com.taarifu.admin.application.service;

import com.taarifu.admin.api.dto.AdminStatsDto;
import com.taarifu.admin.api.spi.ModuleStat;
import com.taarifu.admin.api.spi.ModuleStatsProvider;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.reporting.api.ReportQueryApi;
import com.taarifu.reporting.api.dto.AdminReportDetail;
import com.taarifu.reporting.api.dto.AdminReportPage;
import com.taarifu.reporting.api.dto.AdminReportQuery;
import com.taarifu.reporting.api.dto.ReportStatusCount;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportsAdminService} — the admin overview aggregation + cross-module isolation
 * rules (M14, UC-H06; ADR-0013 §1, ARCHITECTURE §1 "degrade gracefully").
 *
 * <p>Responsibility: proves (a) the report counts come from reporting's published port, (b) the
 * verification-queue depth and flags-pending are sourced from the published stats SPI <b>by stable key</b>
 * — so the admin module needs no identity/moderation import — defaulting to {@code 0} when no provider
 * publishes them, and (c) a throwing stats provider is isolated (treated as {@code 0}) so one module can
 * never break the overview. Mockito only — no Spring, no Docker.</p>
 */
class ReportsAdminServiceTest {

    private final ClockPort clock = () -> Instant.parse("2026-06-23T09:00:00Z");

    private static ModuleStatsProvider provider(String section, ModuleStat... stats) {
        return new ModuleStatsProvider() {
            @Override
            public String section() {
                return section;
            }

            @Override
            public List<ModuleStat> stats() {
                return List.of(stats);
            }
        };
    }

    @Test
    void stats_sourcesReportCountsFromPort_andQueueDepthsFromProvidersByKey() {
        ReportQueryApi reportApi = mock(ReportQueryApi.class);
        when(reportApi.reportCountsByStatus()).thenReturn(List.of(new ReportStatusCount("NEW", 5)));
        when(reportApi.openCaseCount()).thenReturn(12L);
        when(reportApi.slaBreachedCount()).thenReturn(2L);

        var identity = provider("identity",
                new ModuleStat(ReportsAdminService.KEY_VERIFICATION_QUEUE_DEPTH, "Pending verifications", 8));
        var moderation = provider("moderation",
                new ModuleStat(ReportsAdminService.KEY_FLAGS_PENDING, "Flags pending", 4));

        var service = new ReportsAdminService(reportApi, List.of(identity, moderation), clock);
        AdminStatsDto stats = service.stats();

        assertThat(stats.reportsByStatus()).containsExactly(new ReportStatusCount("NEW", 5));
        assertThat(stats.openCases()).isEqualTo(12L);
        assertThat(stats.slaBreachedCases()).isEqualTo(2L);
        assertThat(stats.verificationQueueDepth()).isEqualTo(8L);
        assertThat(stats.flagsPending()).isEqualTo(4L);
        assertThat(stats.generatedAt()).isEqualTo(Instant.parse("2026-06-23T09:00:00Z"));
    }

    @Test
    void stats_missingProviders_defaultDepthsToZero_notFailure() {
        ReportQueryApi reportApi = mock(ReportQueryApi.class);
        when(reportApi.reportCountsByStatus()).thenReturn(List.of());
        when(reportApi.openCaseCount()).thenReturn(0L);
        when(reportApi.slaBreachedCount()).thenReturn(0L);

        var service = new ReportsAdminService(reportApi, List.of(), clock);
        AdminStatsDto stats = service.stats();

        assertThat(stats.verificationQueueDepth()).isZero();
        assertThat(stats.flagsPending()).isZero();
    }

    @Test
    void stats_throwingProvider_isIsolated_othersStillRead() {
        ReportQueryApi reportApi = mock(ReportQueryApi.class);
        when(reportApi.reportCountsByStatus()).thenReturn(List.of());
        when(reportApi.openCaseCount()).thenReturn(0L);
        when(reportApi.slaBreachedCount()).thenReturn(0L);

        ModuleStatsProvider broken = new ModuleStatsProvider() {
            @Override
            public String section() {
                return "broken";
            }

            @Override
            public List<ModuleStat> stats() {
                throw new IllegalStateException("provider down");
            }
        };
        var moderation = provider("moderation",
                new ModuleStat(ReportsAdminService.KEY_FLAGS_PENDING, "Flags pending", 6));

        var service = new ReportsAdminService(reportApi, List.of(broken, moderation), clock);
        AdminStatsDto stats = service.stats();

        // The broken provider is isolated; the healthy moderation key is still read.
        assertThat(stats.flagsPending()).isEqualTo(6L);
        assertThat(stats.verificationQueueDepth()).isZero();
    }

    @Test
    void listReports_and_caseDetail_delegateToPort() {
        ReportQueryApi reportApi = mock(ReportQueryApi.class);
        AdminReportPage page = new AdminReportPage(List.of(), 0, 20, 0);
        AdminReportDetail detail = mock(AdminReportDetail.class);
        AdminReportQuery filter = new AdminReportQuery("NEW", null, null, null);
        UUID reportId = UUID.randomUUID();
        when(reportApi.adminQuery(eq(filter), anyInt(), anyInt())).thenReturn(page);
        when(reportApi.adminDetail(reportId)).thenReturn(detail);

        var service = new ReportsAdminService(reportApi, List.of(), clock);

        assertThat(service.listReports(filter, 0, 20)).isSameAs(page);
        assertThat(service.caseDetail(reportId)).isSameAs(detail);
        verify(reportApi).adminQuery(eq(filter), eq(0), eq(20));
        verify(reportApi).adminDetail(reportId);
    }
}

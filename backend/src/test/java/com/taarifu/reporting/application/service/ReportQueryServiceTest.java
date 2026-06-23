package com.taarifu.reporting.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.reporting.api.dto.AdminReportDetail;
import com.taarifu.reporting.api.dto.AdminReportPage;
import com.taarifu.reporting.api.dto.AdminReportQuery;
import com.taarifu.reporting.api.dto.ReportStatusCount;
import com.taarifu.reporting.application.mapper.ReportingMapper;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.reporting.test.ReportingTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportQueryService}'s admin-console read surface (M14; PRD §10 US-3.4, §18).
 *
 * <p>Responsibility: pins the rules a reviewer must never see silently regress on the
 * {@code admin → reporting} read seam — the queue maps to PII-minimised rows with the correct SLA-breach
 * derivation; an unknown status filter yields an empty page rather than a query/500; the case detail loads
 * the <b>full</b> timeline (public + internal); the dashboard counts derive "open" from the state machine's
 * terminal set; and a missing report is a typed not-found. Mockito only — no database (the repository
 * queries themselves are covered by the Testcontainers integration test).</p>
 */
class ReportQueryServiceTest {

    /** A fixed clock far enough ahead that the fixture's {@code now+1h} due date is already in the past. */
    private final ClockPort clock = () -> Instant.parse("2030-01-01T00:00:00Z");

    private ReportRepository reportRepository;
    private CaseEventRepository caseEventRepository;
    private ReportingMapper mapper;
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        caseEventRepository = mock(CaseEventRepository.class);
        mapper = new ReportingMapper();
        service = new ReportQueryService(reportRepository, caseEventRepository, mapper, clock);
    }

    @Test
    void adminQuery_mapsRows_andDerivesSlaBreach_forActivePastDueCase() {
        IssueCategory category = ReportingTestFixtures.publicCategory("WATER");
        // Fixture report is NEW (active) with dueAt = now+1h; against the 2030 clock it is past-due → breached.
        Report report = ReportingTestFixtures.report(UUID.randomUUID(), category, ReportVisibility.PUBLIC);
        Page<Report> page = new PageImpl<>(List.of(report), Pageable.ofSize(20), 1);
        when(reportRepository.adminSearch(any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        AdminReportPage result = service.adminQuery(new AdminReportQuery(null, null, null, null), 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).slaBreached())
                .as("an active case past its dueAt is SLA-breached")
                .isTrue();
        // PII minimisation: the row is the summary projection (no description/reporter fields exist on it).
        assertThat(result.content().get(0).code()).isEqualTo("TAR-2026-000001");
    }

    @Test
    void adminQuery_unknownStatus_shortCircuitsToEmptyPage_withoutQuerying() {
        AdminReportPage result =
                service.adminQuery(new AdminReportQuery("NOT_A_STATUS", null, null, null), 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        // The repository must NOT be hit for an un-parseable status (a stale filter never 500s the queue).
        verify(reportRepository, org.mockito.Mockito.never())
                .adminSearch(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void adminQuery_passesParsedStatus_andTerminalSet_toRepository() {
        when(reportRepository.adminSearch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));

        service.adminQuery(new AdminReportQuery("in_progress", null, null, null), 0, 20);

        ArgumentCaptor<ReportStatus> status = ArgumentCaptor.forClass(ReportStatus.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ReportStatus>> terminal = ArgumentCaptor.forClass(Collection.class);
        verify(reportRepository).adminSearch(status.capture(), any(), any(), any(),
                terminal.capture(), eq(Instant.parse("2030-01-01T00:00:00Z")), any());
        assertThat(status.getValue()).isEqualTo(ReportStatus.IN_PROGRESS);
        // "open" is single-sourced from the state machine: terminal set must be exactly the terminal states.
        assertThat(terminal.getValue())
                .containsExactlyInAnyOrder(ReportStatus.CLOSED, ReportStatus.REJECTED, ReportStatus.DUPLICATE);
    }

    @Test
    void adminDetail_loadsFullTimeline_notPublicOnly() {
        IssueCategory category = ReportingTestFixtures.publicCategory("WATER");
        Report report = ReportingTestFixtures.report(UUID.randomUUID(), category, ReportVisibility.PUBLIC);
        when(reportRepository.findByPublicIdWithCategory(report.getPublicId()))
                .thenReturn(Optional.of(report));
        when(caseEventRepository.findByReport_PublicId(eq(report.getPublicId()), any()))
                .thenReturn(Page.empty());

        AdminReportDetail detail = service.adminDetail(report.getPublicId());

        assertThat(detail.id()).isEqualTo(report.getPublicId());
        assertThat(detail.slaBreached()).isTrue();
        // The detail MUST use the FULL timeline query (incl. internal notes), not the public-only one (US-3.4).
        verify(caseEventRepository).findByReport_PublicId(eq(report.getPublicId()), any());
        verify(caseEventRepository, org.mockito.Mockito.never())
                .findByReport_PublicIdAndPublicEventTrue(any(), any());
    }

    @Test
    void adminDetail_unknownReport_isNotFound() {
        UUID missing = UUID.randomUUID();
        when(reportRepository.findByPublicIdWithCategory(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adminDetail(missing))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reportCountsByStatus_mapsGroupingRowsToDtos() {
        ReportRepository.StatusCount newCount = statusCount(ReportStatus.NEW, 4);
        ReportRepository.StatusCount closedCount = statusCount(ReportStatus.CLOSED, 9);
        when(reportRepository.countByStatus()).thenReturn(List.of(newCount, closedCount));

        List<ReportStatusCount> result = service.reportCountsByStatus();

        assertThat(result).containsExactlyInAnyOrder(
                new ReportStatusCount("NEW", 4),
                new ReportStatusCount("CLOSED", 9));
    }

    @Test
    void openCaseCount_excludesTerminalStates() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ReportStatus>> terminal = ArgumentCaptor.forClass(Collection.class);
        when(reportRepository.countByStatusNotIn(any())).thenReturn(7L);

        long open = service.openCaseCount();

        assertThat(open).isEqualTo(7L);
        verify(reportRepository).countByStatusNotIn(terminal.capture());
        assertThat(terminal.getValue())
                .containsExactlyInAnyOrder(ReportStatus.CLOSED, ReportStatus.REJECTED, ReportStatus.DUPLICATE);
    }

    @Test
    void slaBreachedCount_evaluatesAgainstClock() {
        when(reportRepository.countSlaBreached(eq(Instant.parse("2030-01-01T00:00:00Z")), any()))
                .thenReturn(3L);

        assertThat(service.slaBreachedCount()).isEqualTo(3L);
    }

    /** Tiny anonymous projection so the grouping mapping can be unit-tested without a DB. */
    private static ReportRepository.StatusCount statusCount(ReportStatus status, long count) {
        return new ReportRepository.StatusCount() {
            @Override
            public ReportStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}

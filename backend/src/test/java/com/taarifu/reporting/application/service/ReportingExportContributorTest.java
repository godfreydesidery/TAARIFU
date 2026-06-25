package com.taarifu.reporting.application.service;

import com.taarifu.reporting.api.dto.ReportingExportView;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.CaseEventType;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportingExportContributor} — reporting's slice of the PDPA ACCESS export
 * (ADR-0016 §4). Proves it returns the subject's own reports + authored events, and {@code null} when the
 * subject has none (so the section is absent). Mockito only; no Docker.
 */
@ExtendWith(MockitoExtension.class)
class ReportingExportContributorTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private CaseEventRepository caseEventRepository;

    private final UUID subject = UUID.randomUUID();

    private ReportingExportContributor contributor() {
        return new ReportingExportContributor(reportRepository, caseEventRepository);
    }

    @Test
    void section_isReports() {
        assertThat(contributor().section()).isEqualTo("reports");
    }

    @Test
    void contribute_returnsOwnReportsAndAuthoredEvents() {
        UUID reportPid = UUID.randomUUID();
        Report report = mock(Report.class);
        when(report.getPublicId()).thenReturn(reportPid);
        when(report.getCode()).thenReturn("TAR-2026-000123");
        when(report.getTitle()).thenReturn("Burst pipe on Kata road");
        when(report.getStatus()).thenReturn(ReportStatus.NEW);
        when(report.getVisibility()).thenReturn(ReportVisibility.PUBLIC);
        when(report.getCreatedAt()).thenReturn(Instant.parse("2026-06-20T08:00:00Z"));

        CaseEvent caseEvent = mock(CaseEvent.class);
        Report owning = mock(Report.class);
        when(owning.getPublicId()).thenReturn(reportPid);
        when(caseEvent.getReport()).thenReturn(owning);
        when(caseEvent.getEventType()).thenReturn(CaseEventType.COMMENT);
        when(caseEvent.getMessage()).thenReturn("Any update?");
        when(caseEvent.getCreatedAt()).thenReturn(Instant.parse("2026-06-21T08:00:00Z"));

        when(reportRepository.findAllByReporterProfileId(subject)).thenReturn(List.of(report));
        when(caseEventRepository.findByActorProfileId(subject)).thenReturn(List.of(caseEvent));

        ReportingExportView view = (ReportingExportView) contributor().contribute(subject);

        assertThat(view).isNotNull();
        assertThat(view.reports()).singleElement().satisfies(r -> {
            assertThat(r.code()).isEqualTo("TAR-2026-000123");
            assertThat(r.status()).isEqualTo("NEW");
            assertThat(r.visibility()).isEqualTo("PUBLIC");
        });
        assertThat(view.authoredEvents()).singleElement().satisfies(e -> {
            assertThat(e.reportPublicId()).isEqualTo(reportPid);
            assertThat(e.eventType()).isEqualTo("COMMENT");
        });
    }

    @Test
    void contribute_returnsNull_whenSubjectHasNothing() {
        lenient().when(reportRepository.findAllByReporterProfileId(subject)).thenReturn(List.of());
        lenient().when(caseEventRepository.findByActorProfileId(subject)).thenReturn(List.of());

        assertThat(contributor().contribute(subject)).isNull();
    }
}

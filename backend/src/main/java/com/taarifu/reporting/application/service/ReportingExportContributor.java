package com.taarifu.reporting.application.service;

import com.taarifu.privacy.api.SubjectExportContributor;
import com.taarifu.reporting.api.dto.ReportingExportView;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reporting's contribution to a data-subject ACCESS export — the issues the subject filed and the timeline
 * entries they authored (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: implements the privacy module's {@link SubjectExportContributor} SPI so the privacy
 * export aggregator can include reporting's data <b>without</b> reaching into reporting's internals
 * (ADR-0013): reporting stays the single reader of its own tables, and privacy depends only on the published
 * SPI interface. Registered automatically as a Spring bean; the privacy {@code SubjectDataExportService}
 * injects every contributor and composes the export by {@link #section()}.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18, ADR-0016 §4):</b> returns {@link ReportingExportView}, the subject's
 * <b>own</b> reports (ticket code, status, the civic title they wrote) and their <b>own</b> authored timeline
 * messages. It never enumerates other reporters' data, exact incident geo-points, or attachment bytes. An
 * anonymous sensitive filing has no reporter linkage by design, so it is correctly <i>absent</i> from any
 * subject's export (the report carries no account to match — D-Q1).</p>
 */
@Service
public class ReportingExportContributor implements SubjectExportContributor {

    /** The export section key reporting fills. */
    private static final String SECTION = "reports";

    private final ReportRepository reportRepository;
    private final CaseEventRepository caseEventRepository;

    /**
     * @param reportRepository    the subject's filed reports.
     * @param caseEventRepository the subject's authored timeline entries.
     */
    public ReportingExportContributor(ReportRepository reportRepository,
                                      CaseEventRepository caseEventRepository) {
        this.reportRepository = reportRepository;
        this.caseEventRepository = caseEventRepository;
    }

    /** {@inheritDoc} */
    @Override
    public String section() {
        return SECTION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the subject's reporting footprint, or {@code null} if the subject filed nothing and authored
     * no timeline entries (so the section is simply absent from the export).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Object contribute(UUID subjectPublicId) {
        List<Report> reports = reportRepository.findAllByReporterProfileId(subjectPublicId);
        List<CaseEvent> events = caseEventRepository.findByActorProfileId(subjectPublicId);
        if (reports.isEmpty() && events.isEmpty()) {
            return null;
        }

        List<ReportingExportView.ReportedIssue> issues = reports.stream()
                .map(r -> new ReportingExportView.ReportedIssue(
                        r.getPublicId(),
                        r.getCode(),
                        r.getTitle(),
                        r.getStatus().name(),
                        r.getVisibility().name(),
                        r.getCreatedAt()))
                .toList();
        List<ReportingExportView.AuthoredEvent> authored = events.stream()
                .map(e -> new ReportingExportView.AuthoredEvent(
                        e.getReport().getPublicId(),
                        e.getEventType().name(),
                        e.getMessage(),
                        e.getCreatedAt()))
                .toList();

        return new ReportingExportView(issues, authored);
    }
}

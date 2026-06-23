package com.taarifu.reporting.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.reporting.api.ReportQueryApi;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reporting's implementation of the published {@link ReportQueryApi} — the synchronous
 * {@code responders → reporting} report-existence validation seam (ADR-0013 §1, §4a; D21).
 *
 * <p>Responsibility: answer "does this report exist?" so the responders module never binds an assignment to
 * a non-existent report, without importing reporting's internals. {@code @Transactional(readOnly = true)};
 * returns only {@code void}/{@code boolean} (no entity crosses the boundary, CLAUDE.md §8).</p>
 */
@Service
@Transactional(readOnly = true)
public class ReportQueryService implements ReportQueryApi {

    private final ReportRepository reportRepository;

    /**
     * @param reportRepository report persistence port (existence checks).
     */
    public ReportQueryService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** {@inheritDoc} */
    @Override
    public void requireExists(UUID reportPublicId) {
        if (!exists(reportPublicId)) {
            throw new ResourceNotFoundException("reporting.report.notFound", reportPublicId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(UUID reportPublicId) {
        return reportPublicId != null && reportRepository.findByPublicId(reportPublicId).isPresent();
    }
}

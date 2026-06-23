package com.taarifu.reporting.application.service;

import com.taarifu.moderation.api.SubjectAuthorQueryApi;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Reporting's implementation of the moderation {@link SubjectAuthorQueryApi} for {@link FlagSubjectType#REPORT}
 * subjects (ADR-0013 §4c; D16). It lets moderation resolve a flagged report to its author for the
 * self-action guard, without moderation importing reporting's internals.
 *
 * <p>Responsibility: given a report public id, return the reporter's account public id, or empty for an
 * <b>anonymous</b> sensitive filing (no reporter linkage — D-Q1) or a non-existent report. The reporter id
 * stored on the report is taken from {@code CurrentUser.requirePublicId()} at file time, so it is already
 * the <b>account public id</b> the moderation grain contract requires (no profile→account round-trip).</p>
 */
@Service
@Transactional(readOnly = true)
public class ReportSubjectAuthorQuery implements SubjectAuthorQueryApi {

    private final ReportRepository reportRepository;

    /**
     * @param reportRepository report persistence port (author lookup by public id).
     */
    public ReportSubjectAuthorQuery(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.REPORT;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> authorOf(UUID subjectId) {
        // getReporterProfileId() is the account public id (set from CurrentUser at file time) or null for an
        // anonymous sensitive report — null is the "no surfaced author" case the moderation guard expects.
        return reportRepository.findByPublicId(subjectId)
                .map(r -> r.getReporterProfileId());
    }
}

package com.taarifu.reporting.application.service;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Reporting's implementation of the moderation {@link SubjectContentQueryApi} for
 * {@link FlagSubjectType#REPORT} subjects (US-12.3, UC-H05; ADR-0018; ADR-0013 §4c). It lets the moderation
 * auto-assist screen resolve a flagged report to its <b>scorable text</b> so the {@code ContentSafety} scorer
 * can run, without moderation importing reporting's internals.
 *
 * <p>Responsibility: given a report public id, return the citizen-authored civic text under review — the
 * report {@link Report#getTitle() title} plus {@link Report#getDescription() description} — for the
 * heuristic scorer to scan for PROFANITY/SPAM/PII signals (ADR-0018 §2). The text is handled
 * <b>transiently</b> by moderation: scored inside the flag/triage transaction and then discarded — never
 * persisted on the queue item, in an event, or in a log (PRD §18, PDPA).</p>
 *
 * <h3>What it deliberately surfaces — and what it does not</h3>
 * <ul>
 *   <li><b>Only the content under review.</b> The returned text is the report's own civic body
 *       (title + description) — the substance a moderator/scorer is judging. It carries no reporter
 *       identity, no ward/geo, no attachment bytes, and no other person's PII the aggregate happens to
 *       reference (the {@code SubjectContentQueryApi} grain contract).</li>
 *   <li><b>An anonymous sensitive report is still resolvable for triage.</b> Auto-assist screens
 *       <i>content</i>, not the reporter; a flagged anonymous report's body must still be scorable so a
 *       human queue item is correctly prioritised by what it actually contains (R20/R21). Anonymity removes
 *       the reporter linkage, not the civic text — the text returned here remains free of the (absent)
 *       reporter's PII either way.</li>
 *   <li><b>Assist only (D-Q8, R21).</b> This port produces input to a <i>screen</i>, never an action: the
 *       text it returns can only cause a queue item to be held-for-review and prioritised — it can never
 *       approve, hide, remove, or sanction. The human pipeline is always the floor.</li>
 * </ul>
 *
 * <p>WHY {@code @Transactional(readOnly = true)}: a pure lookup of one aggregate, no mutation — the same
 * shape as {@link ReportSubjectAuthorQuery} so reporting publishes exactly one bean per moderation concern
 * (author lookup, content lookup) with no new pattern (ADR-0013 §4c).</p>
 */
@Service
@Transactional(readOnly = true)
public class ReportSubjectContentQuery implements SubjectContentQueryApi {

    private final ReportRepository reportRepository;

    /**
     * @param reportRepository report persistence port (content lookup by public id).
     */
    public ReportSubjectContentQuery(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.REPORT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the report's title and description joined as a single scorable block. The two fields are the
     * citizen's civic content (the issue being reported); both are scanned together so a signal in either the
     * short title or the longer body raises the item. A non-existent/soft-deleted report (the
     * {@code @SQLRestriction} hides tombstones) resolves to {@link Optional#empty()} — the auto-assist screen
     * is then skipped and the flagged item still goes to a human moderator (the human-pipeline floor, EI-18).</p>
     */
    @Override
    public Optional<String> contentTextOf(UUID subjectId) {
        if (subjectId == null) {
            return Optional.empty();
        }
        return reportRepository.findByPublicId(subjectId).map(this::scorableText);
    }

    /**
     * Joins the report's title and description into the single block the scorer scans. Kept private so the
     * "what text is scorable" decision lives in one place; it never includes reporter PII, geo, or any field
     * outside the civic content body.
     *
     * @param report the flagged report.
     * @return the title + description as one newline-separated string (always non-null; either field may be
     *         blank but the report contract requires both present).
     */
    private String scorableText(Report report) {
        return report.getTitle() + "\n" + report.getDescription();
    }
}

package com.taarifu.reporting.application.service;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.reporting.test.ReportingTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportSubjectContentQuery} — reporting's implementation of moderation's
 * {@link com.taarifu.moderation.api.SubjectContentQueryApi} for {@link FlagSubjectType#REPORT}
 * (US-12.3, UC-H05; ADR-0018; ADR-0013 §4c).
 *
 * <p>Pins the invariants moderation's auto-assist screen depends on: the port serves the {@code REPORT}
 * subject type; a flagged report resolves to its <b>scorable text</b> (title + description) so the
 * {@code ContentSafety} scorer can run; a non-existent/soft-deleted report resolves to empty so the screen
 * is skipped and the item still goes to a human (the human-pipeline floor, EI-18). Mockito only; no Docker.</p>
 */
class ReportSubjectContentQueryTest {

    private ReportRepository reportRepository;
    private ReportSubjectContentQuery query;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        query = new ReportSubjectContentQuery(reportRepository);
    }

    @Test
    void subjectType_isReport() {
        // The registry key moderation dispatches on — reporting owns exactly the REPORT subject type.
        assertThat(query.subjectType()).isEqualTo(FlagSubjectType.REPORT);
    }

    @Test
    void contentTextOf_existingReport_returnsTitleAndDescription() {
        // The scorer scans the citizen's civic content: title + description joined. This is the text
        // auto-assist screens for PROFANITY/SPAM/PII — fails if the resolved content stops covering the body.
        IssueCategory category = ReportingTestFixtures.publicCategory("WATER");
        Report report = ReportingTestFixtures.report(UUID.randomUUID(), category, ReportVisibility.PUBLIC);
        when(reportRepository.findByPublicId(report.getPublicId())).thenReturn(Optional.of(report));

        Optional<String> text = query.contentTextOf(report.getPublicId());

        assertThat(text).isPresent();
        assertThat(text.get())
                .contains(report.getTitle())
                .contains(report.getDescription());
    }

    @Test
    void contentTextOf_anonymousSensitiveReport_stillResolvesContentForTriage() {
        // Auto-assist screens CONTENT, not the reporter: a flagged anonymous report's body must still be
        // scorable so a human queue item is prioritised by what it contains (R20/R21). Anonymity removes the
        // reporter linkage, not the civic text.
        IssueCategory category = ReportingTestFixtures.sensitiveForcedPrivateCategory("CORRUPTION");
        Report report = ReportingTestFixtures.report(null, category, ReportVisibility.PRIVATE);
        when(reportRepository.findByPublicId(report.getPublicId())).thenReturn(Optional.of(report));

        Optional<String> text = query.contentTextOf(report.getPublicId());

        assertThat(text).isPresent();
        assertThat(text.get()).contains(report.getDescription());
    }

    @Test
    void contentTextOf_missingReport_isEmpty_soScreenIsSkipped() {
        // A non-existent/soft-deleted report resolves to empty — moderation then skips the scorer and the
        // flagged item still reaches a human moderator (EI-18 floor).
        UUID missing = UUID.randomUUID();
        when(reportRepository.findByPublicId(missing)).thenReturn(Optional.empty());

        assertThat(query.contentTextOf(missing)).isEmpty();
    }

    @Test
    void contentTextOf_nullId_isEmpty() {
        // Defensive: a null subject id never hits the repository and resolves to empty (no-op screen).
        assertThat(query.contentTextOf(null)).isEmpty();
    }
}

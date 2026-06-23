package com.taarifu.reporting.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.persistence.CodeGenerator;
import com.taarifu.reporting.api.dto.FileReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.reporting.application.mapper.ReportingMapper;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportStatus;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.port.WardResolver;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.reporting.test.ReportingTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportService} — the M3 integrity invariants (PRD §10, §12.1, §25.3, Appendix D).
 *
 * <p>Responsibility: pins the rules a reviewer must never see silently regress — anonymity is permitted
 * <b>only</b> for sensitive categories; a force-private category overrides the citizen's PUBLIC choice;
 * filing computes the SLA due date and a NEW status; ownership is enforced as not-found; an illegal
 * confirm/dispute is a conflict. Mockito only — no database (the migration/repo wiring is covered by the
 * Testcontainers integration test).</p>
 */
class ReportServiceTest {

    private ReportRepository reportRepository;
    private IssueCategoryRepository categoryRepository;
    private CaseEventRepository caseEventRepository;
    private WardResolver wardResolver;
    private CodeGenerator codeGenerator;
    private ClockPort clock;
    private ReportService service;

    private final UUID reporter = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final UUID constituencyId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        categoryRepository = mock(IssueCategoryRepository.class);
        caseEventRepository = mock(CaseEventRepository.class);
        wardResolver = mock(WardResolver.class);
        codeGenerator = mock(CodeGenerator.class);
        clock = mock(ClockPort.class);
        ReportingMapper mapper = new ReportingMapper();
        service = new ReportService(reportRepository, categoryRepository, caseEventRepository,
                wardResolver, codeGenerator, mapper, clock);

        when(clock.now()).thenReturn(now);
        when(codeGenerator.nextCode(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("TAR-2026-000123");
        when(wardResolver.resolveWard(wardId)).thenReturn(new WardResolver.Resolution(wardId, constituencyId));
        // save() echoes the entity (the publicId/code are already set by the service before save).
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void fileReport_nonSensitive_computesSlaAndStartsNew() {
        IssueCategory category = ReportingTestFixtures.publicCategory("WATER_SANITATION");
        when(categoryRepository.findByPublicId(category.getPublicId())).thenReturn(Optional.of(category));

        FileReportDto request = new FileReportDto(category.getPublicId(), "Bomba", "Maji",
                wardId, null, null, "PUBLIC", false, null);

        ReportDto dto = service.fileReport(reporter, request);

        // SLA: dueAt = now + category TTR (20160 min); status NEW; reporter recorded.
        assertThat(dto.status()).isEqualTo(ReportStatus.NEW.name());
        assertThat(dto.dueAt()).isEqualTo(now.plusSeconds(20160L * 60));
        assertThat(dto.anonymous()).isFalse();
        assertThat(dto.visibility()).isEqualTo(ReportVisibility.PUBLIC.name());
        assertThat(dto.constituencyId()).isEqualTo(constituencyId);
        // An initial timeline event is appended (the case opened).
        verify(caseEventRepository).save(any());
    }

    @Test
    void fileReport_anonymousOnNonSensitive_isRejected() {
        IssueCategory category = ReportingTestFixtures.publicCategory("ROADS");
        when(categoryRepository.findByPublicId(category.getPublicId())).thenReturn(Optional.of(category));

        FileReportDto request = new FileReportDto(category.getPublicId(), "Shimo", "Barabara",
                wardId, null, null, "PUBLIC", true, null);

        // D-Q1: anonymity is permitted ONLY for sensitive categories.
        assertThatThrownBy(() -> service.fileReport(reporter, request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void fileReport_sensitiveForcedPrivate_overridesPublicChoiceAndDropsReporter() {
        IssueCategory category = ReportingTestFixtures.sensitiveForcedPrivateCategory("CORRUPTION");
        when(categoryRepository.findByPublicId(category.getPublicId())).thenReturn(Optional.of(category));

        // Citizen requests PUBLIC and anonymous on a forced-private sensitive category.
        FileReportDto request = new FileReportDto(category.getPublicId(), "Rushwa", "Ofisa amedai rushwa",
                wardId, null, null, "PUBLIC", true, null);

        ReportDto dto = service.fileReport(reporter, request);

        // Forced PRIVATE wins over the citizen's PUBLIC choice (Appendix D.4); anonymity honoured (no reporter).
        assertThat(dto.visibility()).isEqualTo(ReportVisibility.PRIVATE.name());
        assertThat(dto.anonymous()).isTrue();
    }

    @Test
    void getMyReport_notOwner_isNotFound() {
        IssueCategory category = ReportingTestFixtures.publicCategory("HEALTH");
        Report othersReport = ReportingTestFixtures.report(UUID.randomUUID(), category, ReportVisibility.PUBLIC);
        when(reportRepository.findByPublicIdWithCategory(othersReport.getPublicId()))
                .thenReturn(Optional.of(othersReport));

        // Ownership mismatch surfaces as not-found — never reveal another citizen's report exists (§25.3).
        assertThatThrownBy(() -> service.getMyReport(reporter, othersReport.getPublicId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirmResolution_onNonResolvedReport_isConflict() {
        IssueCategory category = ReportingTestFixtures.publicCategory("HEALTH");
        Report myReport = ReportingTestFixtures.report(reporter, category, ReportVisibility.PUBLIC);
        // Report is NEW (not RESOLVED) — confirm/dispute is not valid yet.
        when(reportRepository.findByPublicIdWithCategory(myReport.getPublicId()))
                .thenReturn(Optional.of(myReport));

        assertThatThrownBy(() -> service.confirmResolution(reporter, myReport.getPublicId(), true, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void confirmResolution_confirm_closesReport() {
        IssueCategory category = ReportingTestFixtures.publicCategory("HEALTH");
        Report myReport = ReportingTestFixtures.report(reporter, category, ReportVisibility.PUBLIC);
        myReport.resolve("Imeshughulikiwa");
        when(reportRepository.findByPublicIdWithCategory(myReport.getPublicId()))
                .thenReturn(Optional.of(myReport));

        ReportDto dto = service.confirmResolution(reporter, myReport.getPublicId(), true, "Asante");

        assertThat(dto.status()).isEqualTo(ReportStatus.CLOSED.name());
        assertThat(dto.confirmation()).isTrue();
    }

    @Test
    void confirmResolution_dispute_reopensReport() {
        IssueCategory category = ReportingTestFixtures.publicCategory("HEALTH");
        Report myReport = ReportingTestFixtures.report(reporter, category, ReportVisibility.PUBLIC);
        myReport.resolve("Imeshughulikiwa");
        when(reportRepository.findByPublicIdWithCategory(myReport.getPublicId()))
                .thenReturn(Optional.of(myReport));

        ReportDto dto = service.confirmResolution(reporter, myReport.getPublicId(), false, "Bado halijatatuliwa");

        assertThat(dto.status()).isEqualTo(ReportStatus.REOPENED.name());
        assertThat(dto.confirmation()).isFalse();
    }

    @Test
    void fileReport_unknownCategory_isNotFound() {
        UUID missing = UUID.randomUUID();
        when(categoryRepository.findByPublicId(missing)).thenReturn(Optional.empty());
        FileReportDto request = new FileReportDto(missing, "X", "Y", wardId, null, null, null, false, null);

        assertThatThrownBy(() -> service.fileReport(reporter, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

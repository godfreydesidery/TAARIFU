package com.taarifu.reporting.application.service;

import com.taarifu.reporting.api.UssdReportApi;
import com.taarifu.reporting.api.dto.FileReportDto;
import com.taarifu.reporting.api.dto.ReportDto;
import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportPriority;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.model.enums.RoutingLevel;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UssdReportService} — reporting's implementation of the published {@link UssdReportApi}
 * USSD file/track command port (ADR-0013 §4d; PRD §14, UC-D02).
 *
 * <p>Pins the rules a reviewer must never see silently regress on the {@code ussd → reporting} seam: a USSD
 * filing rides the <b>same</b> {@code ReportService.fileReport} path (so it gets the same lifecycle/SLA/ticket
 * /routing) with a correctly-built request (no GPS/anonymity/custom visibility, a synthesised non-blank
 * title); the track flow is case-insensitive on the ticket code and returns only the PII-free code+status;
 * the category menu maps active categories and clamps a non-positive {@code max}; and the civic-integrity
 * fence holds structurally — no token collaborator is reachable (D18, §23.5). Mockito only, no database.</p>
 */
class UssdReportServiceTest {

    private ReportService reportService;
    private IssueCategoryRepository categoryRepository;
    private ReportRepository reportRepository;
    private UssdReportService service;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        categoryRepository = mock(IssueCategoryRepository.class);
        reportRepository = mock(ReportRepository.class);
        service = new UssdReportService(reportService, categoryRepository, reportRepository);
    }

    /** Filing delegates to ReportService with a USSD-shaped request and returns the minted ticket code. */
    @Test
    void fileFromUssd_buildsUssdRequest_andReturnsTicketCode() {
        UUID reporter = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID wardId = UUID.randomUUID();
        ReportDto filed = reportDtoWithCode("TAR-2026-000042");
        when(reportService.fileReport(eq(reporter), any(FileReportDto.class))).thenReturn(filed);

        String ticket = service.fileFromUssd(reporter, categoryId, wardId, "  Bomba limepasuka  ");

        assertThat(ticket).isEqualTo("TAR-2026-000042");
        ArgumentCaptor<FileReportDto> captor = ArgumentCaptor.forClass(FileReportDto.class);
        verify(reportService).fileReport(eq(reporter), captor.capture());
        FileReportDto req = captor.getValue();
        assertThat(req.categoryId()).isEqualTo(categoryId);
        assertThat(req.wardId()).isEqualTo(wardId);
        assertThat(req.description()).isEqualTo("Bomba limepasuka"); // trimmed
        assertThat(req.title()).isEqualTo("Bomba limepasuka");       // short text → title == description
        // USSD never collects these — the fence/data-minimisation defaults must hold.
        assertThat(req.latitude()).isNull();
        assertThat(req.longitude()).isNull();
        assertThat(req.visibility()).isNull();        // category default applies downstream
        assertThat(req.anonymous()).isFalse();        // USSD filing is identity-linked (T1 account)
        assertThat(req.attachmentRefs()).isNull();
    }

    /** A long free text yields a non-blank title clamped to the title limit (the column is ≤ 200, we cap 80). */
    @Test
    void fileFromUssd_longText_synthesisesClampedTitle() {
        when(reportService.fileReport(any(), any(FileReportDto.class)))
                .thenReturn(reportDtoWithCode("TAR-2026-000001"));
        String longText = "x".repeat(300);

        service.fileFromUssd(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), longText);

        ArgumentCaptor<FileReportDto> captor = ArgumentCaptor.forClass(FileReportDto.class);
        verify(reportService).fileReport(any(), captor.capture());
        assertThat(captor.getValue().title()).hasSizeLessThanOrEqualTo(80);
        assertThat(captor.getValue().description()).hasSize(300);
    }

    /** Track is case-insensitive and returns only the PII-free code + status label. */
    @Test
    void trackByTicket_isCaseInsensitive_andReturnsCodePlusStatus() {
        Report report = report("TAR-2026-000007");
        when(reportRepository.findByCode("TAR-2026-000007")).thenReturn(Optional.of(report));

        Optional<UssdReportApi.UssdReportStatus> status = service.trackByTicket("  tar-2026-000007 ");

        assertThat(status).isPresent();
        assertThat(status.get().ticketCode()).isEqualTo("TAR-2026-000007");
        assertThat(status.get().status()).isEqualTo("NEW");
    }

    /** An unknown / blank ticket yields empty (the caller renders a not-found END — never reveals existence). */
    @Test
    void trackByTicket_unknownOrBlank_isEmpty() {
        when(reportRepository.findByCode(any())).thenReturn(Optional.empty());
        assertThat(service.trackByTicket("TAR-2026-999999")).isEmpty();
        assertThat(service.trackByTicket("  ")).isEmpty();
        assertThat(service.trackByTicket(null)).isEmpty();
    }

    /** topCategories maps active categories to (id, name) and a non-positive max yields an empty list. */
    @Test
    void topCategories_mapsActive_andClampsNonPositiveMax() {
        IssueCategory water = category("WATER", "Maji na Usafi");
        when(categoryRepository.findByActiveTrue(PageRequest.of(0, 3)))
                .thenReturn(new PageImpl<>(List.of(water)));

        List<UssdReportApi.UssdCategoryOption> options = service.topCategories(3);
        assertThat(options).hasSize(1);
        assertThat(options.get(0).categoryId()).isEqualTo(water.getPublicId());
        assertThat(options.get(0).name()).isEqualTo(water.getName());

        assertThat(service.topCategories(0)).isEmpty();
        assertThat(service.topCategories(-1)).isEmpty();
    }

    /**
     * Civic-integrity fence (D18, §23.5): the service is constructed with exactly the report service +
     * category/report repositories — there is no token-bearing collaborator, so the USSD file path cannot
     * read a token balance. Fails if a tokens dependency is ever injected here.
     */
    @Test
    void ussdFilePath_neverConsultsTokens() {
        for (Field f : UssdReportService.class.getDeclaredFields()) {
            assertThat(f.getType().getName().toLowerCase())
                    .as("USSD report service must not depend on tokens (civic-integrity fence D18)")
                    .doesNotContain("token");
        }
    }

    /** Builds a minimal {@link ReportDto} carrying just the ticket code (the only field the port returns). */
    private static ReportDto reportDtoWithCode(String code) {
        return new ReportDto(UUID.randomUUID(), code, UUID.randomUUID(), "Maji", "t", "d",
                UUID.randomUUID(), null, null, null, "PUBLIC", "NEW", "NORMAL", Instant.now(),
                null, null, null, 0L, 0L, false, java.util.List.of(), Instant.now());
    }

    /** Builds a NEW report with the given ticket code (status defaults to NEW on construction). */
    private static Report report(String code) {
        Report r = new Report(UUID.randomUUID(), category("WATER", "Maji na Usafi"), "Bomba limepasuka",
                "Maji yanamwagika", null, UUID.randomUUID(), UUID.randomUUID(), null,
                ReportVisibility.PUBLIC, ReportPriority.NORMAL, Instant.now().plusSeconds(3600));
        r.setCode(code);
        return r;
    }

    /** Builds an active issue category with a public id assigned (production sets it on @PrePersist). */
    private static IssueCategory category(String code, String name) {
        IssueCategory c = new IssueCategory(code, name, null, RoutingLevel.SECTOR_UTILITY,
                2880, 20160, false, false, ReportVisibility.PUBLIC, "water-drop");
        assignPublicId(c, UUID.randomUUID());
        return c;
    }

    /** Reflectively assigns {@code BaseEntity.publicId} for tests (production sets it on @PrePersist). */
    private static void assignPublicId(BaseEntity entity, UUID publicId) {
        try {
            var field = BaseEntity.class.getDeclaredField("publicId");
            field.setAccessible(true);
            field.set(entity, publicId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

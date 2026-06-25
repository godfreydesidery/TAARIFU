package com.taarifu.reporting.infrastructure.adapter;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.common.persistence.CodeGenerator;
import com.taarifu.reporting.application.mapper.ReportingMapper;
import com.taarifu.reporting.application.service.ReportService;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.port.AttachmentValidator;
import com.taarifu.reporting.domain.port.WardResolver;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.reporting.test.ReportingTestFixtures;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportSearchBackfillSource} — the reporting adapter of the search backfill port.
 *
 * <p>Responsibility: pins the discovery-backfill invariants a reviewer must never see silently regress —
 * a PUBLIC report is re-pushed into the index with the public-safe projection; a PRIVATE/sensitive or
 * anonymous report is <b>never</b> upserted (the IDOR/PDPA fence, PRD §25.3); the source scan is batched
 * across pages; the upsert count reflects only the rows actually indexed.</p>
 *
 * <p><b>WHY a REAL {@link ReportService} (not a mock):</b> the whole point of the adapter is that the
 * backfill reuses the live producer's fence + projection so they cannot drift (ADR-0017 §1, DRY). Mocking
 * {@code reindexForDiscovery} would test a stub, not the fence. So this test wires a genuine
 * {@link ReportService} (with a mocked {@link SearchIndexApi}/{@link ReportRepository}) and asserts on the
 * <b>real</b> projection that flows out — exactly what production indexes. Mockito only — no database.</p>
 */
class ReportSearchBackfillSourceTest {

    private ReportRepository reportRepository;
    private SearchIndexApi searchIndexApi;
    private ReportService reportService;
    private ReportSearchBackfillSource backfillSource;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        searchIndexApi = mock(SearchIndexApi.class);
        // A REAL ReportService so the genuine reindexForDiscovery fence + projection is exercised; only its
        // collaborators are stubbed. The backfill never calls the file/lifecycle paths, so most are unused.
        reportService = new ReportService(reportRepository,
                mock(IssueCategoryRepository.class), mock(CaseEventRepository.class), mock(WardResolver.class),
                mock(CodeGenerator.class), new ReportingMapper(), mock(ClockPort.class), mock(OutboxWriter.class),
                mock(AttachmentValidator.class), searchIndexApi);
        backfillSource = new ReportSearchBackfillSource(reportRepository, reportService);
    }

    @Test
    void entityType_isPublicReport() {
        // The orchestrator groups results by this; one adapter owns exactly one type (ADR-0017).
        assertThat(backfillSource.entityType()).isEqualTo(SearchEntityType.PUBLIC_REPORT);
    }

    @Test
    void backfill_upsertsPublicReports_withPublicSafeProjection_andCountsThem() {
        // A PUBLIC report is re-pushed into discovery with the SAME projection the live path builds: title +
        // description snippet + ward/category facets + PUBLIC visibility, public-display + opaque ids only.
        IssueCategory category = ReportingTestFixtures.publicCategory("WATER_SANITATION");
        UUID reporter = UUID.randomUUID();
        Report a = ReportingTestFixtures.report(reporter, category, ReportVisibility.PUBLIC);
        Report b = ReportingTestFixtures.report(UUID.randomUUID(), category, ReportVisibility.PUBLIC);
        whenPagePublic(List.of(a, b));

        long upserted = backfillSource.backfill(searchIndexApi);

        assertThat(upserted).isEqualTo(2L);
        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndexApi, times(2)).upsert(captor.capture());
        verify(searchIndexApi, never()).remove(any(), any());
        // The projection is the live producer's: public-safe, opaque ids only, no PII.
        SearchDocumentUpsert first = captor.getAllValues().get(0);
        assertThat(first.entityType()).isEqualTo(SearchEntityType.PUBLIC_REPORT);
        assertThat(first.entityPublicId()).isEqualTo(a.getPublicId());
        assertThat(first.title()).isEqualTo(a.getTitle());
        assertThat(first.areaId()).isEqualTo(a.getReporterWardId());
        assertThat(first.categoryId()).isEqualTo(category.getPublicId());
        assertThat(first.visibility()).isEqualTo(SearchVisibility.PUBLIC);
    }

    @Test
    void backfill_neverIndexesAnonymousReport_andExcludesItFromTheCount() {
        // The IDOR/PDPA fence (PRD §25.3): an anonymous report (no reporter linkage) is NEVER upserted into
        // public discovery — the shared fence REMOVES it (idempotent) instead, and it is not counted. This
        // FAILS the moment an anonymous report leaks into the index via the backfill.
        IssueCategory category = ReportingTestFixtures.publicCategory("WATER_SANITATION");
        Report publicReport = ReportingTestFixtures.report(UUID.randomUUID(), category, ReportVisibility.PUBLIC);
        Report anonymous = ReportingTestFixtures.report(null, category, ReportVisibility.PUBLIC); // no reporter
        assertThat(anonymous.isAnonymous()).isTrue();
        whenPagePublic(List.of(publicReport, anonymous));

        long upserted = backfillSource.backfill(searchIndexApi);

        assertThat(upserted).isEqualTo(1L); // only the public, non-anonymous report
        verify(searchIndexApi, times(1)).upsert(any());
        // The anonymous row is positively removed (defence-in-depth), never upserted.
        verify(searchIndexApi).remove(SearchEntityType.PUBLIC_REPORT, anonymous.getPublicId());
    }

    @Test
    void backfill_neverIndexesPrivateReport() {
        // A PRIVATE/sensitive report routed through the shared fence is REMOVED, never upserted — and not
        // counted. (In production the row query filters visibility=PUBLIC so PRIVATE rows are not even read;
        // this asserts the fence itself still excludes one if it ever reached the adapter — belt-and-braces.)
        IssueCategory category = ReportingTestFixtures.sensitiveForcedPrivateCategory("CORRUPTION");
        Report privateReport = ReportingTestFixtures.report(null, category, ReportVisibility.PRIVATE);
        whenPagePublic(List.of(privateReport));

        long upserted = backfillSource.backfill(searchIndexApi);

        assertThat(upserted).isZero();
        verify(searchIndexApi, never()).upsert(any());
        verify(searchIndexApi).remove(SearchEntityType.PUBLIC_REPORT, privateReport.getPublicId());
    }

    @Test
    void backfill_isBatched_walksEveryPage() {
        // The scan is paged (BATCH_SIZE) and must walk EVERY page to exhaustion — a backfill that stops after
        // page one would silently miss most of the corpus. Two full pages + a trailing partial page.
        IssueCategory category = ReportingTestFixtures.publicCategory("ROADS");
        List<Report> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(ReportingTestFixtures.report(UUID.randomUUID(), category, ReportVisibility.PUBLIC));
        }
        // Page the 5 rows as 2 + 2 + 1 to assert the do/while advances and terminates on the last page.
        when(reportRepository.findByVisibilityWithCategoryOrderById(eq(ReportVisibility.PUBLIC), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    return slice(all, p.getPageNumber(), 2);
                });

        long upserted = backfillSource.backfill(searchIndexApi);

        assertThat(upserted).isEqualTo(5L);
        verify(searchIndexApi, times(5)).upsert(any());
        // Three pages requested (0,1,2): the walk did not stop early and did not loop past the end.
        verify(reportRepository, times(3))
                .findByVisibilityWithCategoryOrderById(eq(ReportVisibility.PUBLIC), any(Pageable.class));
    }

    @Test
    void backfill_emptySource_returnsZero_neverThrows() {
        // The contract: an empty source returns 0, never throws (the orchestrator must be able to drive a
        // module with no rows). No index call of any kind.
        whenPagePublic(List.of());

        long upserted = backfillSource.backfill(searchIndexApi);

        assertThat(upserted).isZero();
        verify(searchIndexApi, never()).upsert(any());
        verify(searchIndexApi, never()).remove(any(), any());
    }

    /** Stubs a single full page of the given PUBLIC reports (no further pages). */
    private void whenPagePublic(List<Report> reports) {
        when(reportRepository.findByVisibilityWithCategoryOrderById(eq(ReportVisibility.PUBLIC), any(Pageable.class)))
                .thenReturn(new PageImpl<>(reports, PageRequest.of(0, 200), reports.size()));
    }

    /** Builds the page at {@code pageNumber} of {@code size} over {@code all} (for the batching walk test). */
    private static Page<Report> slice(List<Report> all, int pageNumber, int size) {
        int from = Math.min(pageNumber * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageImpl<>(all.subList(from, to), PageRequest.of(pageNumber, size), all.size());
    }
}

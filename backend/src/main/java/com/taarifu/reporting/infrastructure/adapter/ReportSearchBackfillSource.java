package com.taarifu.reporting.infrastructure.adapter;

import com.taarifu.reporting.application.service.ReportService;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.port.SearchBackfillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting's adapter of the search module's {@link SearchBackfillSource} port — re-pushes this module's
 * <b>pre-existing PUBLIC reports</b> into the discovery index for the {@code PUBLIC_REPORT} entity type
 * (ADR-0017 follow-up "a one-off backfill job per owner"; ADR-0013 §7 / ARCHITECTURE §7 cross-module
 * {@code domain.port} injection — the same shape as {@code SmsGateway}/{@code Geocoder}).
 *
 * <p><b>The problem it solves:</b> {@link com.taarifu.search.api.SearchIndexApi} is populated on
 * reporting's <i>write path</i> going forward ({@code ReportService} file/confirm/resolve/transition).
 * Reports filed <b>before</b> that producer was wired — or after a search schema/analyzer change — are
 * missing from the index. Search cannot rebuild the projection itself (the searchable text and, critically,
 * the privacy/visibility decision live entirely inside reporting), so the direction stays owner→search: the
 * search {@code SearchBackfillService} discovers this bean and calls {@link #backfill(SearchIndexApi)}.</p>
 *
 * <p><b>🔒 The fence is NOT re-implemented here (DRY — the load-bearing decision):</b> this adapter does
 * <b>not</b> decide what is indexable or build the projection itself. It pages PUBLIC report rows and routes
 * <b>every</b> one through the live producer's own {@link ReportService#reindexForDiscovery(Report,
 * com.taarifu.reporting.domain.model.IssueCategory) reindexForDiscovery} — the <i>exact same</i> method the
 * file/resolve/transition write path calls. So the index-vs-no-index fence (PRD §18, §25.3, PDPA — never
 * index a PRIVATE/sensitive/anonymous/deleted report) and the public projection shape can never drift between
 * the live path and the backfill: a report this adapter indexes is, by construction, a report the live path
 * would have indexed identically. Anonymity is excluded by that shared fence (a PUBLIC+anonymous report
 * cannot occur — a sensitive category forces PRIVATE — but the fence screens it anyway, belt-and-braces);
 * soft-deleted reports are excluded by the entity's {@code @SQLRestriction}; PRIVATE rows are never even
 * read (the row query filters {@code visibility = PUBLIC}).</p>
 *
 * <p><b>Idempotent + batched + PII-free:</b> every push goes through {@code index.upsert}, keyed on
 * {@code (PUBLIC_REPORT, publicId)}, so a re-run lands the same live rows in place — never a duplicate. The
 * source read is paged ({@link #BATCH_SIZE}) to keep memory and the data budget lean (PRD §15) and the
 * category is join-fetched in the page query to avoid an N+1 across the corpus. The projection carries
 * public-display data + opaque ids only (the shared producer guarantees it); this adapter logs <b>counts
 * only</b> — never a title, snippet, id, or any PII.</p>
 *
 * <p><b>Same-module dependencies only:</b> this adapter depends on reporting's own {@link ReportRepository}
 * and {@link ReportService} (the projection lives in the owner) and on search's published {@code api}
 * ({@link SearchIndexApi}, passed in) — never a sibling's {@code domain}/{@code infrastructure}. Implementing
 * the search {@code domain.port} is the sanctioned cross-module injection (ModuleBoundaryTest carve-out (b)).</p>
 *
 * <p><b>Note on {@code ISSUE_CATEGORY}:</b> {@link SearchEntityType#ISSUE_CATEGORY} is nominally owned by
 * reporting, but reporting has <b>no live producer</b> pushing categories to the index today (the category
 * write path does not call {@link SearchIndexApi}). A backfill must mirror the live producer exactly — it
 * must never invent an index projection that the write path does not maintain, or the two would diverge. So
 * this adapter covers {@code PUBLIC_REPORT} only; an {@code ISSUE_CATEGORY} backfill ships together with (and
 * reusing) a category index-on-write producer, not before it.</p>
 */
@Component
public class ReportSearchBackfillSource implements SearchBackfillSource {

    private static final Logger log = LoggerFactory.getLogger(ReportSearchBackfillSource.class);

    /**
     * The page size of the source scan. Bounded so a full-corpus backfill never loads every report into
     * memory at once and the data budget stays lean (PRD §15). Each page is processed then released; the
     * walk advances by page index until the source is exhausted.
     */
    private static final int BATCH_SIZE = 200;

    private final ReportRepository reportRepository;
    private final ReportService reportService;

    /**
     * @param reportRepository reporting's own report persistence port — the source of PUBLIC rows to re-push
     *                         (paged, category join-fetched).
     * @param reportService    reporting's own lifecycle service — its
     *                         {@link ReportService#reindexForDiscovery(Report,
     *                         com.taarifu.reporting.domain.model.IssueCategory)} is the single shared fence +
     *                         projection this backfill reuses so the index decision cannot drift from the live path.
     */
    public ReportSearchBackfillSource(ReportRepository reportRepository, ReportService reportService) {
        this.reportRepository = reportRepository;
        this.reportService = reportService;
    }

    /** {@inheritDoc} */
    @Override
    public SearchEntityType entityType() {
        return SearchEntityType.PUBLIC_REPORT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages PUBLIC reports (category join-fetched, ordered by primary key for a stable walk) and routes each
     * through the live producer's shared fence ({@link ReportService#reindexForDiscovery}), which upserts a
     * public-safe row and removes (idempotent no-op) any that is not. Counts only the rows actually upserted —
     * a {@code false} return from the fence (a row that should not be discoverable) is excluded from the total.
     * Runs read-only: the source reads happen in this bounded read transaction, while each {@code index.upsert}
     * opens its own short write transaction inside the search module — so the whole corpus is never held in one
     * giant transaction (per the orchestrator's recovery model).</p>
     *
     * @param index the search module's inbound index port (passed by the orchestrator; the shared fence calls
     *              {@code upsert}/{@code remove} on the {@link SearchIndexApi} {@code ReportService} already holds).
     * @return the number of PUBLIC reports upserted into discovery ({@code >= 0}).
     */
    @Override
    @Transactional(readOnly = true)
    public long backfill(SearchIndexApi index) {
        long upserted = 0L;
        int pageNumber = 0;
        Page<Report> page;
        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
            page = reportRepository.findByVisibilityWithCategoryOrderById(ReportVisibility.PUBLIC, pageable);
            for (Report report : page.getContent()) {
                // Reuse the EXACT live fence + projection — never a parallel copy here (DRY, ADR-0017 §1). The
                // shared method upserts a public-safe row and returns true; it removes (idempotent) and returns
                // false for any non-public-safe row, which we do not count as indexed.
                if (reportService.reindexForDiscovery(report, report.getCategory())) {
                    upserted++;
                }
            }
            pageNumber++;
        } while (page.hasNext());

        // PII-free: counts only — never a title/snippet/id (PRD §18).
        log.info("Reporting search backfill: upserted {} PUBLIC_REPORT row(s) across {} page(s)",
                upserted, pageNumber);
        return upserted;
    }
}

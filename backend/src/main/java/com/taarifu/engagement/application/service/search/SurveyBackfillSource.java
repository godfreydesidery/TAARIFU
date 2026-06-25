package com.taarifu.engagement.application.service.search;

import com.taarifu.engagement.application.service.SurveyService;
import com.taarifu.engagement.domain.model.Survey;
import com.taarifu.engagement.domain.model.enums.SurveyStatus;
import com.taarifu.engagement.domain.repository.SurveyRepository;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.port.SearchBackfillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Engagement's adapter of the search module's {@link SearchBackfillSource} port for the {@code POLL} entity type —
 * re-pushes this module's <b>pre-existing publicly-visible surveys and polls</b> (non-DRAFT) into the discovery
 * index (ADR-0017 follow-up "a one-off backfill job per owner"; ADR-0013 §7 cross-module {@code domain.port}
 * injection — ModuleBoundaryTest carve-out (b)).
 *
 * <p>Covers BOTH the {@code SURVEY} and {@code POLL} {@code SurveyType}s: engagement models them as one
 * {@code Survey} aggregate, and the search owner maps both to {@link SearchEntityType#POLL} (one value serves
 * both — see the enum). So this single adapter owns the entire {@code POLL} entity type.</p>
 *
 * <p><b>The problem it solves:</b> {@link SearchIndexApi} is populated on engagement's <i>write path</i> going
 * forward ({@link SurveyService} create/open). Surveys/polls created <b>before</b> that producer was wired — or
 * after a search schema/analyzer change — are missing from the index. Search cannot rebuild the projection itself
 * (the searchable text and the public-vs-draft visibility decision live inside engagement), so the direction
 * stays owner→search: the search {@code SearchBackfillService} discovers this bean and calls
 * {@link #backfill(SearchIndexApi)}.</p>
 *
 * <p><b>🔒 The fence is NOT re-implemented here (DRY):</b> this adapter pages the <i>public statuses</i> and
 * routes <b>every</b> row through the live producer's own {@link SurveyService#reindexForDiscovery(Survey)} — the
 * <i>exact same</i> method the create/open write path calls. So the index-vs-no-index fence (PRD §18 — never index
 * a DRAFT, never the questions JSON or any response payload) and the public projection shape can never drift
 * between the live path and the backfill. A DRAFT cannot appear (the status query excludes it) and the shared
 * fence screens it anyway (belt-and-braces); soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 *
 * <p><b>Idempotent + batched + PII-free:</b> every push goes through {@code index.upsert} (inside the shared
 * producer), keyed on {@code (POLL, publicId)}, so a re-run lands the same live rows in place — never a duplicate.
 * The source read is paged ({@link #BATCH_SIZE}, ordered by primary key). The projection carries the public title
 * + description snippet only — NEVER the questions JSON, NEVER any response payload, NEVER the responder list (the
 * shared producer guarantees it); this adapter logs <b>counts only</b>.</p>
 */
@Component
public class SurveyBackfillSource implements SearchBackfillSource {

    private static final Logger log = LoggerFactory.getLogger(SurveyBackfillSource.class);

    /**
     * The page size of the source scan. Bounded so a full-corpus backfill never loads every survey into memory at
     * once and the data budget stays lean (PRD §15).
     */
    private static final int BATCH_SIZE = 200;

    /**
     * The publicly-visible survey statuses to re-push — everything except {@code DRAFT} (PRD §22.6). Mirrors the
     * live public-list fence; the shared {@link SurveyService#reindexForDiscovery(Survey)} re-checks visibility
     * per-row regardless (DRY), so this is the read filter, not the authority.
     */
    private static final List<SurveyStatus> PUBLIC_STATUSES = List.of(
            SurveyStatus.SCHEDULED, SurveyStatus.OPEN,
            SurveyStatus.CLOSED, SurveyStatus.ARCHIVED);

    private final SurveyRepository surveys;
    private final SurveyService surveyService;

    /**
     * @param surveys       engagement's own survey persistence port — the source of public rows to re-push.
     * @param surveyService engagement's own lifecycle service — its
     *                      {@link SurveyService#reindexForDiscovery(Survey)} is the single shared fence +
     *                      projection this backfill reuses so the index decision cannot drift from the live path.
     */
    public SurveyBackfillSource(SurveyRepository surveys, SurveyService surveyService) {
        this.surveys = surveys;
        this.surveyService = surveyService;
    }

    /** {@inheritDoc} */
    @Override
    public SearchEntityType entityType() {
        return SearchEntityType.POLL;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages the public-status surveys/polls (ordered by primary key) and routes each through the live producer's
     * shared fence ({@link SurveyService#reindexForDiscovery(Survey)}), which upserts the public projection and
     * returns {@code true}. Counts only the rows actually upserted. Runs read-only at this level; each
     * {@code index.upsert} opens its own short write transaction inside the search module.</p>
     *
     * @param index the search module's inbound index port (passed by the orchestrator).
     * @return the number of public surveys/polls upserted into discovery ({@code >= 0}).
     */
    @Override
    @Transactional(readOnly = true)
    public long backfill(SearchIndexApi index) {
        long upserted = 0L;
        int pageNumber = 0;
        Page<Survey> page;
        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE, Sort.by("id").ascending());
            page = surveys.findByStatusIn(PUBLIC_STATUSES, pageable);
            for (Survey survey : page.getContent()) {
                // Reuse the EXACT live fence + projection — never a parallel copy here (DRY, ADR-0017 §1).
                if (surveyService.reindexForDiscovery(survey)) {
                    upserted++;
                }
            }
            pageNumber++;
        } while (page.hasNext());

        // PII-free: counts only — never a title/snippet/id (PRD §18).
        log.info("Engagement search backfill: upserted {} POLL row(s) across {} page(s)", upserted, pageNumber);
        return upserted;
    }
}

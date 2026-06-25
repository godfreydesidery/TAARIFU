package com.taarifu.engagement.application.service.search;

import com.taarifu.engagement.application.service.QuestionService;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.enums.QuestionStatus;
import com.taarifu.engagement.domain.repository.QuestionRepository;
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
 * Engagement's adapter of the search module's {@link SearchBackfillSource} port for the {@code QUESTION} entity
 * type — re-pushes this module's <b>pre-existing publicly-visible Q&amp;A questions</b> (OPEN/ANSWERED) into the
 * discovery index (ADR-0017 follow-up "a one-off backfill job per owner"; ADR-0013 §7 cross-module
 * {@code domain.port} injection — ModuleBoundaryTest carve-out (b)).
 *
 * <p><b>The problem it solves:</b> {@link SearchIndexApi} is populated on engagement's <i>write path</i> going
 * forward ({@link QuestionService} ask/answer). Questions asked <b>before</b> that producer was wired — or after a
 * search schema/analyzer change — are missing from the index. Search cannot rebuild the projection itself (the
 * searchable text and the public-vs-hidden visibility decision live inside engagement), so the direction stays
 * owner→search: the search {@code SearchBackfillService} discovers this bean and calls
 * {@link #backfill(SearchIndexApi)}.</p>
 *
 * <p><b>🔒 The fence is NOT re-implemented here (DRY):</b> this adapter pages the <i>public statuses</i>
 * ({@code OPEN}/{@code ANSWERED}) and routes <b>every</b> row through the live producer's own
 * {@link QuestionService#reindexForDiscovery(Question)} — the <i>exact same</i> method the ask/answer write path
 * calls. So the index-vs-no-index fence (PRD §18 — never index a DECLINED/MODERATED question, never the asker id)
 * and the public projection shape can never drift. A DECLINED/MODERATED question cannot appear (the status query
 * excludes it) and the shared fence screens it anyway (belt-and-braces); soft-deleted rows are excluded by the
 * entity's {@code @SQLRestriction}.</p>
 *
 * <p><b>Idempotent + batched + PII-free:</b> every push goes through {@code index.upsert} (inside the shared
 * producer), keyed on {@code (QUESTION, publicId)}, so a re-run lands the same live rows in place — never a
 * duplicate. The source read is paged ({@link #BATCH_SIZE}, ordered by primary key). The projection carries the
 * public body snippet + the target-rep facet only — NEVER the asker id (the shared producer sets
 * {@code authoredByAccountId} null deliberately); this adapter logs <b>counts only</b>.</p>
 */
@Component
public class QuestionBackfillSource implements SearchBackfillSource {

    private static final Logger log = LoggerFactory.getLogger(QuestionBackfillSource.class);

    /**
     * The page size of the source scan. Bounded so a full-corpus backfill never loads every question into memory
     * at once and the data budget stays lean (PRD §15).
     */
    private static final int BATCH_SIZE = 200;

    /**
     * The publicly-visible question statuses to re-push — {@code OPEN}/{@code ANSWERED} (PRD §22.6). Mirrors the
     * live public-list fence; the shared {@link QuestionService#reindexForDiscovery(Question)} re-checks visibility
     * per-row regardless (DRY), so this is the read filter, not the authority.
     */
    private static final List<QuestionStatus> PUBLIC_STATUSES =
            List.of(QuestionStatus.OPEN, QuestionStatus.ANSWERED);

    private final QuestionRepository questions;
    private final QuestionService questionService;

    /**
     * @param questions       engagement's own question persistence port — the source of public rows to re-push.
     * @param questionService engagement's own lifecycle service — its
     *                        {@link QuestionService#reindexForDiscovery(Question)} is the single shared fence +
     *                        projection this backfill reuses so the index decision cannot drift from the live path.
     */
    public QuestionBackfillSource(QuestionRepository questions, QuestionService questionService) {
        this.questions = questions;
        this.questionService = questionService;
    }

    /** {@inheritDoc} */
    @Override
    public SearchEntityType entityType() {
        return SearchEntityType.QUESTION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages the public-status questions (ordered by primary key) and routes each through the live producer's
     * shared fence ({@link QuestionService#reindexForDiscovery(Question)}), which upserts the public projection and
     * returns {@code true}. Counts only the rows actually upserted. Runs read-only at this level; each
     * {@code index.upsert} opens its own short write transaction inside the search module.</p>
     *
     * @param index the search module's inbound index port (passed by the orchestrator).
     * @return the number of public questions upserted into discovery ({@code >= 0}).
     */
    @Override
    @Transactional(readOnly = true)
    public long backfill(SearchIndexApi index) {
        long upserted = 0L;
        int pageNumber = 0;
        Page<Question> page;
        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE, Sort.by("id").ascending());
            page = questions.findByStatusIn(PUBLIC_STATUSES, pageable);
            for (Question question : page.getContent()) {
                // Reuse the EXACT live fence + projection — never a parallel copy here (DRY, ADR-0017 §1).
                if (questionService.reindexForDiscovery(question)) {
                    upserted++;
                }
            }
            pageNumber++;
        } while (page.hasNext());

        // PII-free: counts only — never a body snippet/id (PRD §18).
        log.info("Engagement search backfill: upserted {} QUESTION row(s) across {} page(s)", upserted, pageNumber);
        return upserted;
    }
}

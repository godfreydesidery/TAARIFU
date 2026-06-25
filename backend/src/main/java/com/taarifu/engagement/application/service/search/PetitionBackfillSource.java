package com.taarifu.engagement.application.service.search;

import com.taarifu.engagement.application.service.PetitionService;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import com.taarifu.engagement.domain.repository.PetitionRepository;
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
 * Engagement's adapter of the search module's {@link SearchBackfillSource} port for the {@code PETITION} entity
 * type — re-pushes this module's <b>pre-existing publicly-visible petitions</b> (non-DRAFT) into the discovery
 * index (ADR-0017 follow-up "a one-off backfill job per owner"; ADR-0013 §7 / ARCHITECTURE §7 cross-module
 * {@code domain.port} injection — the same shape as {@code SmsGateway}/{@code Geocoder}, ModuleBoundaryTest
 * carve-out (b)).
 *
 * <p><b>The problem it solves:</b> {@link SearchIndexApi} is populated on engagement's <i>write path</i> going
 * forward ({@link PetitionService} create/activate/sign). Petitions created <b>before</b> that producer was wired
 * — or after a search schema/analyzer change — are missing from the index. Search cannot rebuild the projection
 * itself (the searchable text and the public-vs-draft visibility decision live entirely inside engagement), so the
 * direction stays owner→search: the search {@code SearchBackfillService} discovers this bean and calls
 * {@link #backfill(SearchIndexApi)}.</p>
 *
 * <p><b>🔒 The fence is NOT re-implemented here (DRY — the load-bearing decision):</b> this adapter does
 * <b>not</b> decide what is indexable or build the projection itself. It pages the <i>public statuses</i> (the
 * same {@link #PUBLIC_STATUSES} the live public list uses) and routes <b>every</b> row through the live producer's
 * own {@link PetitionService#reindexForDiscovery(Petition)} — the <i>exact same</i> method the create/activate/
 * sign write path calls. So the index-vs-no-index fence (PRD §18 — never index a DRAFT) and the public projection
 * shape can never drift between the live path and the backfill: a petition this adapter indexes is, by
 * construction, one the live path would have indexed identically. A DRAFT cannot even appear (the status query
 * excludes it), and the shared fence screens it anyway (belt-and-braces); soft-deleted rows are excluded by the
 * entity's {@code @SQLRestriction}.</p>
 *
 * <p><b>Idempotent + batched + PII-free:</b> every push goes through {@code index.upsert} (inside the shared
 * producer), keyed on {@code (PETITION, publicId)}, so a re-run lands the same live rows in place — never a
 * duplicate. The source read is paged ({@link #BATCH_SIZE}, ordered by primary key for a stable walk) to keep
 * memory and the data budget lean (PRD §15). The projection carries public-display data + opaque ids only (the
 * shared producer guarantees it: title + body snippet + status keyword — never the signer list, the creator, or
 * any PII); this adapter logs <b>counts only</b> — never a title, snippet, id, or any PII.</p>
 *
 * <p><b>Same-module dependencies only:</b> this adapter depends on engagement's own {@link PetitionRepository}
 * and {@link PetitionService} (the projection lives in the owner) and on search's published {@code api}
 * ({@link SearchIndexApi}, passed in) + {@code domain.port} ({@link SearchBackfillSource}) — never a sibling's
 * {@code domain.model}/{@code infrastructure}. Implementing the search {@code domain.port} is the sanctioned
 * cross-module injection (ModuleBoundaryTest carve-out (b)).</p>
 */
@Component
public class PetitionBackfillSource implements SearchBackfillSource {

    private static final Logger log = LoggerFactory.getLogger(PetitionBackfillSource.class);

    /**
     * The page size of the source scan. Bounded so a full-corpus backfill never loads every petition into memory
     * at once and the data budget stays lean (PRD §15). Each page is processed then released; the walk advances by
     * page index until the source is exhausted.
     */
    private static final int BATCH_SIZE = 200;

    /**
     * The publicly-visible petition statuses to re-push — everything except {@code DRAFT} (PRD §22.6). Mirrors the
     * live public-list fence; the shared {@link PetitionService#reindexForDiscovery(Petition)} re-checks visibility
     * per-row regardless (DRY), so this is the read filter, not the authority.
     */
    private static final List<PetitionStatus> PUBLIC_STATUSES = List.of(
            PetitionStatus.ACTIVE, PetitionStatus.SUCCEEDED,
            PetitionStatus.RESPONDED, PetitionStatus.CLOSED);

    private final PetitionRepository petitions;
    private final PetitionService petitionService;

    /**
     * @param petitions       engagement's own petition persistence port — the source of public rows to re-push
     *                        (paged, ordered by primary key).
     * @param petitionService engagement's own lifecycle service — its
     *                        {@link PetitionService#reindexForDiscovery(Petition)} is the single shared fence +
     *                        projection this backfill reuses so the index decision cannot drift from the live path.
     */
    public PetitionBackfillSource(PetitionRepository petitions, PetitionService petitionService) {
        this.petitions = petitions;
        this.petitionService = petitionService;
    }

    /** {@inheritDoc} */
    @Override
    public SearchEntityType entityType() {
        return SearchEntityType.PETITION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages the public-status petitions (ordered by primary key for a stable walk) and routes each through the
     * live producer's shared fence ({@link PetitionService#reindexForDiscovery(Petition)}), which upserts the
     * public projection and returns {@code true}. Counts only the rows actually upserted. Runs read-only: the
     * source reads happen in this bounded read transaction, while each {@code index.upsert} opens its own short
     * write transaction inside the search module — so the whole corpus is never held in one giant transaction (per
     * the orchestrator's recovery model).</p>
     *
     * @param index the search module's inbound index port (passed by the orchestrator; the shared fence calls
     *              {@code upsert}/{@code remove} on the {@link SearchIndexApi} {@link PetitionService} already holds).
     * @return the number of public petitions upserted into discovery ({@code >= 0}).
     */
    @Override
    @Transactional(readOnly = true)
    public long backfill(SearchIndexApi index) {
        long upserted = 0L;
        int pageNumber = 0;
        Page<Petition> page;
        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE, Sort.by("id").ascending());
            page = petitions.findByStatusIn(PUBLIC_STATUSES, pageable);
            for (Petition petition : page.getContent()) {
                // Reuse the EXACT live fence + projection — never a parallel copy here (DRY, ADR-0017 §1). The
                // shared method upserts a public-safe row and returns true; it returns false (and removes,
                // idempotent) for any non-public row, which we do not count as indexed.
                if (petitionService.reindexForDiscovery(petition)) {
                    upserted++;
                }
            }
            pageNumber++;
        } while (page.hasNext());

        // PII-free: counts only — never a title/snippet/id (PRD §18).
        log.info("Engagement search backfill: upserted {} PETITION row(s) across {} page(s)", upserted, pageNumber);
        return upserted;
    }
}

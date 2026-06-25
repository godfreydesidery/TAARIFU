package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.application.service.AnnouncementService;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
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
 * Communications' adapter of the search module's {@link SearchBackfillSource} port — re-pushes this module's
 * <b>pre-existing PUBLISHED announcements</b> into the discovery index for the {@link
 * SearchEntityType#ANNOUNCEMENT} entity type (ADR-0017 follow-up "a one-off backfill job per owner";
 * ADR-0013 §7 / ARCHITECTURE §7 cross-module {@code domain.port} injection — the same shape as
 * {@code SmsGateway}/{@code Geocoder}, ModuleBoundaryTest carve-out (b)).
 *
 * <p><b>The problem it solves:</b> {@link SearchIndexApi} is populated on communications' <i>write path</i>
 * going forward — the went-live funnel ({@code AnnouncementService.publish}/{@code approveAndPublish}) upserts a
 * public projection and the expiry/unpublish path removes it (ADR-0017 §1). Announcements that went live
 * <b>before</b> that producer was wired — or after a search schema/analyzer change — are missing from the index.
 * Search cannot rebuild the projection itself: the searchable text and, critically, the privacy/visibility
 * decision (is this a DRAFT/moderation-held/SCHEDULED/EXPIRED row that must NOT be discoverable?) live entirely
 * inside communications. So the direction stays owner→search: the search {@code SearchBackfillService} discovers
 * this bean and calls {@link #backfill(SearchIndexApi)}.</p>
 *
 * <p><b>🔒 The fence is NOT re-implemented here (DRY — the load-bearing decision, ADR-0017 §1):</b> this adapter
 * does <b>not</b> decide what is indexable or build the projection itself. It pages {@code PUBLISHED} rows and
 * routes <b>every</b> one through the live producer's own
 * {@link AnnouncementService#reindexForDiscovery(Announcement) reindexForDiscovery} — the <i>exact same</i> method
 * the publish/approve/expire write path calls. So the index-vs-no-index fence (PRD §18 — never index a
 * DRAFT/moderation-held/scheduled/expired announcement) and the public projection shape (public title +
 * localised snippet + area/category facets only; never the full body, the {@code moderationHeld} flag,
 * attachments, or the schedule) can never drift between the live path and the backfill: an announcement this
 * adapter indexes is, by construction, one the live path would have indexed identically. Moderation-held and
 * draft rows are excluded by the {@code status = PUBLISHED} row filter (they are never even read) and screened
 * again by the shared fence (belt-and-braces); soft-deleted rows are excluded by the entity's
 * {@code @SQLRestriction}.</p>
 *
 * <p><b>Idempotent + batched + PII-free:</b> every push goes through {@code index.upsert}, keyed on
 * {@code (ANNOUNCEMENT, publicId)}, so a re-run lands the same live rows in place — never a duplicate (the same
 * idempotency the live write path relies on). The source read is paged ({@link #BATCH_SIZE}) to keep memory and
 * the data budget lean (PRD §15). The projection carries public-display data + opaque ids only (the shared
 * producer guarantees it); this adapter logs <b>counts only</b> — never a title, snippet, id, or any PII
 * (PRD §18).</p>
 *
 * <p><b>Same-module dependencies only:</b> this adapter depends on communications' own
 * {@link AnnouncementRepository} and {@link AnnouncementService} (the projection lives in the owner) and on
 * search's published {@code api} ({@link SearchIndexApi}, passed in) — never a sibling's {@code domain}/
 * {@code infrastructure}. Implementing the search {@code domain.port} is the sanctioned cross-module injection
 * (ARCHITECTURE §7; ModuleBoundaryTest carve-out (b)).</p>
 */
@Component
public class AnnouncementSearchBackfillSource implements SearchBackfillSource {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementSearchBackfillSource.class);

    /**
     * The page size of the source scan. Bounded so a full-corpus backfill never loads every announcement into
     * memory at once and the data budget stays lean (PRD §15). Each page is processed then released; the walk
     * advances by page index until the source is exhausted.
     */
    private static final int BATCH_SIZE = 200;

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementService announcementService;

    /**
     * @param announcementRepository communications' own announcement persistence port — the source of
     *                               {@code PUBLISHED} rows to re-push (paged, ordered by primary key).
     * @param announcementService    communications' own publish service — its
     *                               {@link AnnouncementService#reindexForDiscovery(Announcement)} is the single
     *                               shared fence + projection this backfill reuses so the index decision cannot
     *                               drift from the live write path (DRY, ADR-0017 §1).
     */
    public AnnouncementSearchBackfillSource(AnnouncementRepository announcementRepository,
                                            AnnouncementService announcementService) {
        this.announcementRepository = announcementRepository;
        this.announcementService = announcementService;
    }

    /** {@inheritDoc} */
    @Override
    public SearchEntityType entityType() {
        return SearchEntityType.ANNOUNCEMENT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages {@code PUBLISHED} announcements (ordered by primary key for a stable walk) and routes each through
     * the live producer's shared fence ({@link AnnouncementService#reindexForDiscovery(Announcement)}), which
     * upserts a public-safe row and returns {@code true}. Counts only the rows actually upserted — a {@code false}
     * return from the fence (a row that should not be discoverable) is excluded from the total; given the
     * {@code status = PUBLISHED} row filter every loaded row is expected to upsert, but the count stays honest if
     * the fence ever decides otherwise. The source reads run in this bounded read transaction while each
     * {@code index.upsert} opens its own short write transaction inside the search module — so the whole corpus is
     * never held in one giant transaction (per the orchestrator's per-row recovery model).</p>
     *
     * @param index the search module's inbound index port (passed by the orchestrator; the shared fence calls
     *              {@code upsert}/{@code remove} on the {@link SearchIndexApi} {@code AnnouncementService} holds).
     * @return the number of {@code PUBLISHED} announcements upserted into discovery ({@code >= 0}).
     */
    @Override
    @Transactional(readOnly = true)
    public long backfill(SearchIndexApi index) {
        long upserted = 0L;
        int pageNumber = 0;
        Page<Announcement> page;
        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
            page = announcementRepository.findByStatusOrderById(AnnouncementStatus.PUBLISHED, pageable);
            for (Announcement announcement : page.getContent()) {
                // Reuse the EXACT live fence + projection — never a parallel copy here (DRY, ADR-0017 §1). The
                // shared method upserts a public-safe (PUBLISHED) row and returns true; it removes (idempotent)
                // and returns false for any non-public-safe row, which we do not count as indexed.
                if (announcementService.reindexForDiscovery(announcement)) {
                    upserted++;
                }
            }
            pageNumber++;
        } while (page.hasNext());

        // PII-free: counts only — never a title/snippet/id (PRD §18).
        log.info("Communications search backfill: upserted {} ANNOUNCEMENT row(s) across {} page(s)",
                upserted, pageNumber);
        return upserted;
    }
}

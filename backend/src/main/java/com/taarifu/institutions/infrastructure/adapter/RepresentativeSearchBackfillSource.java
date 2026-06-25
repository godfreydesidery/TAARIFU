package com.taarifu.institutions.infrastructure.adapter;

import com.taarifu.institutions.application.service.RepresentativeSearchProjection;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
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

/**
 * Institutions' implementation of search's outbound {@link SearchBackfillSource} port for the
 * {@link SearchEntityType#REPRESENTATIVE} entity type — re-pushes the representative <b>directory</b> into the
 * cross-entity discovery index on an admin-triggered reindex (ADR-0017 backfill follow-up; ADR-0013 §7 /
 * ModuleBoundaryTest carve-out (b): a {@code search.domain.port} an owner legitimately <i>implements</i>, the
 * same cross-module {@code domain.port} injection pattern as {@code SmsGateway}/{@code Geocoder}).
 *
 * <p><b>The problem this solves:</b> {@code SearchIndexApi} indexes a representative on the institutions write
 * path going forward; representatives created <b>before</b> that producer was wired (the seeded directory) are
 * absent from the index. Search cannot rebuild that projection itself — the searchable text and the
 * status→visibility decision live entirely in institutions. So search asks institutions, via this port, to
 * re-push its own rows (direction owner→search, no reach-in — ADR-0017).</p>
 *
 * <p><b>How it stays consistent with the live path:</b> it builds each row's projection with the very same
 * {@link RepresentativeSearchProjection#of(Representative)} the live write path uses, so the projection AND the
 * visibility fence (PENDING_VERIFICATION → {@code STAFF}; SITTING/FORMER → {@code PUBLIC}) are identical and
 * cannot drift (DRY). Because every push goes through {@code SearchIndexApi.upsert} keyed on
 * {@code (REPRESENTATIVE, publicId)}, the backfill is <b>idempotent</b> — safe to re-run, never a duplicate.</p>
 *
 * <p><b>Batched (data-budget / memory):</b> it pages the directory in fixed-size batches (never loading the
 * whole table at once), fetch-joining each row's constituency/ward so the projection never fires an N+1 (PRD
 * §15). Each batch is read in its own short read-only transaction so the LAZY seat associations resolve and no
 * connection is held across the whole corpus (the orchestrator is deliberately non-transactional — per-row
 * idempotent upserts are the unit of recovery).</p>
 *
 * <p><b>🔒 PII:</b> exactly as the live producer — the projection carries public-display data + opaque ids only
 * (seat label, role facets, area id), never a phone, national/voter ID, free GPS, {@code bio}, or the linked
 * account id. This adapter logs counts only, never the corpus (PRD §18).</p>
 */
@Component
public class RepresentativeSearchBackfillSource implements SearchBackfillSource {

    private static final Logger log = LoggerFactory.getLogger(RepresentativeSearchBackfillSource.class);

    /**
     * Backfill page size — a balance between round-trips and the per-batch heap/transaction footprint. Public
     * directory rows are tiny (a label + a few ids after projection), so a few hundred per page is comfortable
     * while keeping each batch's transaction short (PRD §15).
     */
    private static final int BATCH_SIZE = 200;

    /**
     * Stable batch ordering by primary key. WHY a deterministic sort: paging without an {@code ORDER BY} can
     * skip or repeat rows between page reads under concurrent writes; ordering by {@code id} makes the page
     * window stable and the run reproducible (and the upsert is idempotent regardless).
     */
    private static final Sort BATCH_SORT = Sort.by(Sort.Direction.ASC, "id");

    private final RepresentativeRepository representativeRepository;

    /**
     * @param representativeRepository the institutions persistence port the backfill pages (fetch-joining the
     *                                 constituency/ward the projection needs — {@code findAllForSearchBackfill}).
     */
    public RepresentativeSearchBackfillSource(RepresentativeRepository representativeRepository) {
        this.representativeRepository = representativeRepository;
    }

    /** {@inheritDoc} */
    @Override
    public SearchEntityType entityType() {
        return SearchEntityType.REPRESENTATIVE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages the entire live directory in {@value #BATCH_SIZE}-row batches and upserts each row's public
     * projection through {@code index.upsert}. Returns the total rows upserted; an empty directory returns 0.
     * Idempotent and visibility-faithful (see the type Javadoc).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public long backfill(SearchIndexApi index) {
        long upserted = 0L;
        int pageNumber = 0;
        Page<Representative> page;
        do {
            Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE, BATCH_SORT);
            page = representativeRepository.findAllForSearchBackfill(pageable);
            for (Representative rep : page.getContent()) {
                // Same projection + same visibility fence as the live write path — idempotent on (REPRESENTATIVE,
                // publicId). PENDING → STAFF, SITTING/FORMER → PUBLIC, all decided in RepresentativeSearchProjection.
                index.upsert(RepresentativeSearchProjection.of(rep));
                upserted++;
            }
            pageNumber++;
        } while (page.hasNext());

        // Counts only — never a label/seat/id of any indexed row (PRD §18).
        log.info("Representative search backfill upserted {} public directory row(s)", upserted);
        return upserted;
    }
}

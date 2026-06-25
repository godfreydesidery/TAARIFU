package com.taarifu.responders.infrastructure.adapter;

import com.taarifu.responders.application.mapper.OrganisationSearchProjection;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.repository.OrganisationRepository;
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
 * The responders module's {@link SearchBackfillSource} adapter for the {@link SearchEntityType#ORGANISATION}
 * source — re-pushes every pre-existing organisation's public directory projection into the cross-entity
 * discovery index on an admin-triggered reindex (ADR-0017 follow-up "a one-off backfill job per owner";
 * ADR-0013 §7 cross-module {@code domain.port} injection).
 *
 * <p><b>Why this adapter exists:</b> {@code ResponderAdminService} indexes an organisation on its write path
 * going forward (create/update/verify), so any organisation that existed <b>before</b> the producer was wired
 * (or after a search schema/analyzer change) is missing from the index. Search cannot rebuild that projection
 * itself — the searchable text and the visibility decision live entirely in this module. So the direction stays
 * <b>owner → search</b>: search discovers this Spring bean (via the {@code List<SearchBackfillSource>} it
 * injects) and drives {@link #backfill(SearchIndexApi)}, which pages through this module's organisations and
 * re-pushes each through {@link SearchIndexApi#upsert} (ADR-0017 §1).</p>
 *
 * <h3>The fence cannot drift (DRY)</h3>
 * <p>This adapter builds each projection with the <b>same</b> {@link OrganisationSearchProjection} the live
 * write path uses — exactly what the {@code SearchBackfillSource} contract demands ("reuse the live producer's
 * projection/visibility logic ... so the fence cannot drift"). So what is indexed (name + type facet, no PII —
 * PRD §18) and the PUBLIC/STAFF visibility decision ({@link Organisation#isPubliclyListable()} → PUBLIC, else
 * STAFF — §24.4) are identical between the live producer and the backfill. There is no separate fence here to
 * keep in sync.</p>
 *
 * <h3>Indexes ALL organisations — not only the publicly-listable ones (and why that is correct)</h3>
 * <p>The live producer upserts on <b>every</b> organisation create/update/verify regardless of status, setting
 * {@code STAFF} visibility for a PENDING/unverified/suspended org and {@code PUBLIC} only when it is publicly
 * listable. To make the backfill <b>idempotent and faithful to the live path</b>, this adapter pages over
 * <b>all non-deleted</b> organisations ({@link OrganisationRepository#findAllBy(Pageable)}) and re-pushes each
 * with its projection-decided visibility. So a staff-visible unverified org is correctly re-indexed as
 * {@code STAFF} (discoverable by staff, never by a guest/citizen — the index's own visibility filter enforces
 * the public/STAFF split at query time), exactly as the live path would. Soft-deleted organisations are excluded
 * by the entity's {@code @SQLRestriction} and are therefore never re-pushed (they were {@code remove}d from the
 * index on deletion). No citizen PII is ever involved — an organisation's contacts are the body's own public
 * details, and those are not even pushed (the projection carries name + type facet only).</p>
 *
 * <h3>Batched + idempotent</h3>
 * <p>It reads organisations in bounded pages ({@link #PAGE_SIZE}) to keep memory and the data budget lean
 * (PRD §15), ordered by {@code id} for a stable, complete walk. Because each push goes through
 * {@code upsert} (keyed on {@code (ORGANISATION, publicId)}), the backfill is idempotent — safe to re-run, never
 * a duplicate row (ADR-0017 §1).</p>
 *
 * <h3>Boundary (ADR-0013 / ModuleBoundaryTest)</h3>
 * <p>This is a {@code responders.infrastructure.adapter} class implementing a {@code search.domain.port}
 * interface — the sanctioned cross-module {@code domain.port} injection pattern (the {@code SmsGateway}/
 * {@code Geocoder} precedent; {@code ModuleBoundaryTest} carve-out (b)). Its cross-module references are
 * {@code search.api.SearchIndexApi} (a published {@code ..api..} contract — allowed), the
 * {@code search.domain.port.SearchBackfillSource} interface (carve-out (b)), and the
 * {@code search.domain.model.enums.SearchEntityType} value enum (carve-out (c)). It reaches into none of
 * search's encapsulated internals; the suite stays GREEN.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18):</b> pushes public-display data + the opaque {@code publicId} only (never a
 * contact detail, GPS point, or any citizen PII); logs counts only, never a name. The reindex orchestrator
 * isolates a failure of this source from the others (its own try/catch), so a transient DB issue mid-walk
 * degrades organisation coverage only — the operator re-runs after the fix (idempotent).</p>
 */
@Component
public class OrganisationSearchBackfillSource implements SearchBackfillSource {

    private static final Logger log = LoggerFactory.getLogger(OrganisationSearchBackfillSource.class);

    /** Page size for the bounded source walk — lean enough to cap memory, large enough to limit round-trips. */
    private static final int PAGE_SIZE = 200;

    private final OrganisationRepository organisationRepository;
    private final OrganisationSearchProjection projection;

    /**
     * @param organisationRepository this module's organisation persistence — paged over non-deleted rows for the
     *                               complete walk (the {@code @SQLRestriction} excludes soft-deleted orgs).
     * @param projection             the shared organisation→{@code SearchDocumentUpsert} projection (same fence
     *                               as the live producer — DRY).
     */
    public OrganisationSearchBackfillSource(OrganisationRepository organisationRepository,
                                            OrganisationSearchProjection projection) {
        this.organisationRepository = organisationRepository;
        this.projection = projection;
    }

    /** {@inheritDoc} This source owns the {@link SearchEntityType#ORGANISATION} entity type. */
    @Override
    public SearchEntityType entityType() {
        return SearchEntityType.ORGANISATION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages over all non-deleted organisations (ordered by {@code id} for a stable, complete walk) and
     * re-pushes each one's projection through {@link SearchIndexApi#upsert} — visibility decided by the shared
     * {@link OrganisationSearchProjection} (PUBLIC when publicly listable, else STAFF), exactly as the live
     * path. Idempotent (upsert keyed on {@code (ORGANISATION, publicId)}); returns the count upserted. Read in
     * a read-only transaction so the lazy collections are not needed and the walk holds a single short
     * connection per page.</p>
     *
     * @param index the search module's inbound index port to upsert each projection through.
     * @return the number of organisation rows upserted ({@code >= 0}).
     */
    @Override
    @Transactional(readOnly = true)
    public long backfill(SearchIndexApi index) {
        long upserted = 0L;
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));
        Page<Organisation> page;
        do {
            page = organisationRepository.findAllBy(pageable);
            for (Organisation org : page.getContent()) {
                index.upsert(projection.project(org));
                upserted++;
            }
            pageable = page.nextPageable();
        } while (page.hasNext());

        log.info("ORGANISATION search backfill upserted {} organisation projection(s)", upserted);
        return upserted;
    }
}

package com.taarifu.search.domain.port;

import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.domain.model.enums.SearchEntityType;

/**
 * The search module's <b>outbound port</b> that each owning module implements so its <i>pre-existing</i>
 * searchable rows can be (re)populated into the discovery index on demand (ADR-0017 follow-up "a one-off
 * backfill job per owner"; ADR-0013 §7 cross-module {@code domain.port} injection).
 *
 * <p><b>The problem this solves:</b> {@link SearchIndexApi} indexes on the owner's write path going forward, so
 * data created <b>before</b> the producers were wired (or after a schema/analyzer change) is missing from the
 * index. Search cannot rebuild those projections itself — the searchable text and, critically, the
 * <b>privacy/visibility decision</b> (is this report anonymous/PRIVATE? is this org publicly listable? is this
 * representative VERIFIED?) live entirely inside the owner. Replicating that fence in search would duplicate it
 * (DRY) and force search to reach into a sibling's {@code domain}/{@code infrastructure} (boundary violation,
 * ADR-0013 §1). So the direction stays <b>owner→search</b>: search asks each owner to re-push its own rows.</p>
 *
 * <p><b>The contract (what an implementing adapter MUST do):</b> page through this source's own PUBLISHED,
 * public-listable entities and, for each, build the <i>same</i> {@link com.taarifu.search.api.dto.SearchDocumentUpsert}
 * projection its live producer builds and call {@link SearchIndexApi#upsert}. It MUST reuse the live producer's
 * projection/visibility logic (ideally the very same private method) so the fence cannot drift — never index a
 * DRAFT/unpublished/anonymous/PRIVATE/sensitive/suspended-author row, and set {@code STAFF} visibility exactly
 * where the live path would. Because it goes through {@code upsert} (keyed on {@code (entityType, publicId)}), the
 * backfill is <b>idempotent</b>: safe to re-run, never a duplicate. The adapter SHOULD batch its source reads to
 * keep memory and the data budget lean (PRD §15).</p>
 *
 * <p><b>🔒 PII:</b> exactly as the live producer — public-display data + opaque ids only, never a phone,
 * national/voter ID, free GPS point, or private body text (PRD §18). The implementation logs counts only.</p>
 *
 * <p><b>WHY a {@code domain.port} (not an {@code api} inbound method):</b> this is an interface the owning
 * modules <i>implement</i> and search <i>calls</i> — the same cross-module {@code domain.port} injection pattern
 * the {@code SmsGateway}/{@code Geocoder} adapters use (ARCHITECTURE §7; ModuleBoundaryTest carve-out (b)). Search
 * discovers every Spring bean implementing this port and drives them all, so adding a new searchable source is
 * additive: the new module ships its adapter, no change to search. Until an owner ships its adapter, that source
 * simply contributes nothing to the backfill (correct-but-incomplete, never a leak) — see CENTRAL NEEDS.</p>
 */
public interface SearchBackfillSource {

    /**
     * The entity type this source backfills — used only for the per-source status/label in the reindex report
     * (the orchestrator groups results by it). One adapter owns exactly one {@link SearchEntityType}.
     *
     * @return this source's entity type (never {@code null}).
     */
    SearchEntityType entityType();

    /**
     * Re-pushes <b>all</b> of this source's currently-indexable rows into the index via {@code index.upsert},
     * batched, honouring visibility exactly like the live producer (see the type contract). Idempotent — re-runs
     * land the same rows, never duplicates. Implementations must not throw for an empty source (return 0).
     *
     * <p>The orchestrator calls this inside its own bounded unit of work and isolates a failure of one source
     * from the others, so a single owner's outage degrades that source's coverage only — never the whole job.</p>
     *
     * @param index the search module's inbound index port to upsert each public projection through (never
     *              {@code null}); the owner builds the same projection it builds on its write path.
     * @return the number of rows this source upserted into the index ({@code >= 0}).
     */
    long backfill(SearchIndexApi index);
}

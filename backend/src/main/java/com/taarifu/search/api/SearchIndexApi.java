package com.taarifu.search.api;

import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;

import java.util.UUID;

/**
 * The search module's <b>published inbound port</b> for owning modules to feed the discovery index
 * (ADR-0017 §1; the same published-{@code api}-port shape as {@code tokens.api.TokenLedgerApi} — ADR-0013 §1).
 *
 * <p>Responsibility: let each owning module <b>push</b> a public, flat read-projection of its own searchable
 * entity (a representative, organisation/responder, announcement, issue-category, or public report) into the
 * {@code search_document} index when it creates/updates/removes that entity. The direction is strictly
 * <b>owner → search</b>: search depends on nobody and reaches into no sibling's {@code domain}/
 * {@code infrastructure}; every owner depends only on this {@code api} interface (no cycle, boundary closed —
 * ARCHITECTURE §3.2). This is the "index-on-write" / CQRS read-model population pattern, and is exactly the
 * request an extracted OpenSearch service would receive later (ARCHITECTURE §10).</p>
 *
 * <p><b>WHY push, not search pulling:</b> the searchable text lives in the owner and is on its write path; the
 * existing async events carry ids/codes only — never titles/bodies (ADR-0014 §1, PRD §18) — so an outbox
 * handler could not build a text index without a synchronous reach back into producers. Pushing a flat
 * projection keeps search ignorant of every source schema and keeps the boundary clean (ADR-0017 §1).</p>
 *
 * <p><b>🔒 Callers pass public-display data + opaque ids only — never PII</b> (PRD §18): see
 * {@link SearchDocumentUpsert}. The implementation logs counts/ids only, never the corpus.</p>
 *
 * <p><b>Wiring status (this increment):</b> the implementation, the index table, and the query land now; the
 * <i>producer calls</i> — each owning module invoking {@link #upsert}/{@link #remove} on its write path — are a
 * cross-module wiring step owned by those modules (parallel-build isolation). Until they are wired, the index
 * is correct-but-empty and discovery degrades to "no results", never to a leak (ADR-0017 §1, CENTRAL NEEDS).</p>
 */
public interface SearchIndexApi {

    /**
     * Inserts or updates (idempotent <b>upsert</b>) the index projection for one source entity, keyed on
     * {@code (entityType, entityPublicId)}. Re-pushing the same source updates its single live row, never
     * duplicates (the live-scoped unique index is the DB backstop — ADR-0017 §2). Call this whenever the
     * source entity is created or its searchable/visibility fields change.
     *
     * @param upsert the owner's public projection (never {@code null}; carries no PII).
     */
    void upsert(SearchDocumentUpsert upsert);

    /**
     * Removes a source entity from discovery (soft-delete of its projection row), e.g. when the owner deletes,
     * unpublishes, or hard-hides it. Idempotent: removing an absent/already-removed projection is a no-op.
     *
     * <p>Note: this is a hard removal from discovery. Author-level visibility maintenance (hiding a suspended
     * author's rows to {@code STAFF}) is driven asynchronously by the moderation outbox handler (ADR-0017 §3),
     * not by this call.</p>
     *
     * @param entityType     the kind of entity (never {@code null}).
     * @param entityPublicId the source aggregate's public id (never {@code null}).
     */
    void remove(SearchEntityType entityType, UUID entityPublicId);
}

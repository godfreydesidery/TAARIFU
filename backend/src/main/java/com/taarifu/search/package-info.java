/**
 * search module — cross-entity discovery over the public civic graph (ADR-0017; PRD discovery; ARCHITECTURE §7
 * {@code SearchPort} "Postgres FTS (MVP), OpenSearch (later)").
 *
 * <p>Responsibility: one keyword search, ranked and paged, over <b>representatives</b> ({@code institutions}),
 * <b>organisations/responders</b> ({@code responders}), <b>announcements</b> ({@code communications}),
 * <b>issue-categories</b> and <b>public reports</b> ({@code reporting}) — filterable by entity type, area
 * (Ward-or-coarser), and category. It owns a single denormalised, full-text read-projection table
 * ({@code search_document}, Postgres {@code tsvector} + GIN, migration V146) — the search index. The full
 * record is never returned: a hit carries the source aggregate's public id so the client re-reads the record
 * from its owning module (which re-applies its own fine-grained visibility on that read).</p>
 *
 * <p><b>Population (index-on-write / CQRS read model):</b> each owning module <b>pushes</b> a flat public
 * projection of its own entity through the published inbound port {@link com.taarifu.search.api.SearchIndexApi}
 * ({@code upsert}/{@code remove}) — direction owner→search, so search depends on nobody and reaches into no
 * sibling's {@code domain}/{@code infrastructure}; everybody depends only on search's {@code api} (no cycle,
 * boundary closed — ARCHITECTURE §3.2, ADR-0013 §1). The existing async events carry ids/codes only (never
 * titles/bodies — ADR-0014 §1, PRD §18), so a push of the searchable text is the correct mechanism, not an
 * outbox handler reaching back into producers.</p>
 *
 * <p><b>Visibility maintenance (outbox):</b> search registers one {@code DomainEventHandler}
 * ({@link com.taarifu.search.application.service.SearchVisibilityMaintenanceHandler}) on the existing
 * {@code MODERATION_SANCTION_APPLIED} event — on a {@code SUSPEND} it hides that author's indexed rows out of
 * public discovery (idempotent, ids-only), reusing the ADR-0014 outbox seam (ADR-0017 §3).</p>
 *
 * <p><b>Query/security:</b> {@code GET /search} is {@code permitAll()} for the public civic graph; the
 * private/sensitive ({@code STAFF}) tier is gated <b>server-side</b> — {@code STAFF}-visibility rows are
 * returned only to staff callers, so private rows are filtered out of a guest/citizen result set, never 403'd
 * per row (anti-enumeration, PRD §18, ADR-0017 §4).</p>
 *
 * <p><b>🔒 PII discipline:</b> the index, the push DTO, the consumed event, and the result DTO carry public
 * display data + opaque ids only — never a phone, national/voter ID, free GPS, or private body text. Services
 * log counts/ids only, never the corpus (PRD §18, CLAUDE.md §12).</p>
 *
 * <p><b>Reindex/backfill (admin-triggered):</b> data created before the producers were wired is not in the
 * index. {@link com.taarifu.search.application.service.SearchBackfillService} (behind
 * {@code POST /search/admin/reindex}, ADMIN/ROOT) re-populates it by driving every owning module's
 * {@link com.taarifu.search.domain.port.SearchBackfillSource} adapter — each owner re-pushes its own public
 * projections through {@link com.taarifu.search.api.SearchIndexApi} (owner→search, same fence as the live path,
 * idempotent upsert-by-source-key). Search owns no projection/privacy logic for backfill; it just orchestrates.
 * A status read ({@code GET /search/admin/reindex/status}) reports the live index size + last-run receipt
 * (ADR-0017 backfill follow-up).</p>
 *
 * <p><b>Cross-module wiring status (CENTRAL; owned by each producing module, not search):</b></p>
 * <ul>
 *   <li><b>Producer calls:</b> each owning module invokes {@link com.taarifu.search.api.SearchIndexApi#upsert}
 *       on its write path (DONE for representatives/orgs/announcements/categories/public-reports/petitions/
 *       polls/questions, phase-2 waves 1-3) and {@code remove} on delete/unpublish.</li>
 *   <li><b>Backfill source adapters (DONE):</b> each owning module ships a {@code @Component} implementing
 *       {@link com.taarifu.search.domain.port.SearchBackfillSource} that pages its own PUBLISHED, public-listable
 *       rows and re-pushes each via {@code SearchIndexApi.upsert}, REUSING its live producer's projection/
 *       visibility logic (never re-deriving the fence in search). One adapter per {@code SearchEntityType}:
 *       REPRESENTATIVE (institutions), ORGANISATION (responders), ANNOUNCEMENT (communications), ISSUE_CATEGORY +
 *       PUBLIC_REPORT (reporting), PETITION + POLL + QUESTION (engagement) — all wired. The orchestrator
 *       ({@code SearchBackfillService}) discovers every registered source and reindexes each; an owner that ever
 *       lacks an adapter simply contributes nothing (correct-but-incomplete, never a leak).</li>
 *   <li><b>Security allow-list:</b> {@code "/search/**"} is in {@code SecurityConfig.PUBLIC_GET_PATTERNS} (DONE,
 *       phase-2). The admin reindex endpoints are gated by {@code @PreAuthorize} method security regardless of
 *       the URL filter (admin surfaces are never merely URL-public — SecurityConfig §42).</li>
 *   <li><b>Follow-ups (ADR-0017 revisit):</b> a per-document {@code ContentRemoved} hide handler when that event
 *       exists; a Swahili dictionary/{@code unaccent} + synonym thesaurus to replace the {@code simple} FTS
 *       config; OpenSearch extraction behind the same {@code SearchIndexApi} + {@code GET /search} contracts when
 *       Postgres FTS is outgrown (ARCHITECTURE §10).</li>
 * </ul>
 */
package com.taarifu.search;

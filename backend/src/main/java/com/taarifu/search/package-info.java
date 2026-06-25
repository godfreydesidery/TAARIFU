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
 * <p><b>// TODO(wiring) — CENTRAL / cross-module (owned outside this module; parallel-build isolation):</b></p>
 * <ul>
 *   <li><b>Producer calls:</b> each owning module invokes {@link com.taarifu.search.api.SearchIndexApi#upsert}
 *       on its write path (institutions on representative create/update; responders on org create/update;
 *       communications on announcement publish/unpublish; reporting on category create + public-report
 *       create/visibility-change) and {@code remove} on delete/unpublish. Until wired, the index is
 *       correct-but-empty (discovery degrades to "no results", never to a leak).</li>
 *   <li><b>Security allow-list:</b> add {@code "/search/**"} to {@code SecurityConfig.PUBLIC_GET_PATTERNS}
 *       (GET) so a guest can reach the public search; {@code @PreAuthorize("permitAll()")} alone does not make
 *       the URL reachable unauthenticated.</li>
 *   <li><b>Follow-ups (ADR-0017 revisit):</b> a one-off backfill job per owner; a per-document
 *       {@code ContentRemoved} hide handler when that event exists; a Swahili dictionary/{@code unaccent} +
 *       synonym thesaurus to replace the {@code simple} FTS config; OpenSearch extraction behind the same
 *       {@code SearchIndexApi} + {@code GET /search} contracts when Postgres FTS is outgrown (ARCHITECTURE §10).</li>
 * </ul>
 */
package com.taarifu.search;

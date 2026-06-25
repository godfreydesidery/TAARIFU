package com.taarifu.search.api.dto;

import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;

import java.util.UUID;

/**
 * The projection an owning module <b>pushes</b> to the search index for one of its searchable entities
 * (ADR-0017 §1). It is the parameter of {@link com.taarifu.search.api.SearchIndexApi#upsert}.
 *
 * <p>Responsibility: carry the small, public, searchable slice of a representative / organisation /
 * announcement / issue-category / public-report from its owner into the index, owner→search (no reach-in —
 * ADR-0013 §1). The owner builds this from data it already holds on its write path; search stays ignorant of
 * every source's schema (one flat shape, not five join queries — DRY).</p>
 *
 * <p><b>🔒 Public-display data + opaque ids only — never PII</b> (PRD §18, ADR-0017 §1): supply a public
 * {@link #title}, public localised snippets, free {@link #keywords}, and opaque UUIDs. Never put a phone,
 * national/voter ID, free GPS point, or private body text here — the index is a discovery surface, and
 * {@link #authoredByAccountId} (used only for visibility maintenance) is never returned to a reader.</p>
 *
 * <p>WHY a record in {@code search.api.dto} (not an entity): events/ports carry DTOs/ids only, never a JPA
 * entity (ARCHITECTURE §3.2, CLAUDE.md §8). This keeps the owning module's compile surface free of search's
 * {@code domain} and lets search become a remote service later (ARCHITECTURE §10) with this as its request body.</p>
 *
 * @param entityType          the kind of entity (never {@code null}).
 * @param entityPublicId      the source aggregate's public id — the upsert identity + the client's re-read key
 *                            (never {@code null}).
 * @param title               the public display label (never {@code null}/blank).
 * @param snippetSw           a short localised Swahili public preview, or {@code null}.
 * @param snippetEn           a short localised English public preview, or {@code null}.
 * @param keywords            owner-supplied extra search terms (Swahili synonyms, codes), or {@code null}.
 * @param areaId              the Ward-or-coarser area public id for the area filter, or {@code null}.
 * @param categoryId          the issue-category public id for the category filter, or {@code null}.
 * @param visibility          the visibility tier — {@code PUBLIC} for public civic data, {@code STAFF} for
 *                            private/sensitive/in-flight rows (never {@code null}).
 * @param authoredByAccountId the author's opaque <b>account</b> public id (for visibility maintenance —
 *                            ADR-0017 §3), or {@code null} for authorless entities (categories, the directory).
 */
public record SearchDocumentUpsert(
        SearchEntityType entityType,
        UUID entityPublicId,
        String title,
        String snippetSw,
        String snippetEn,
        String keywords,
        UUID areaId,
        UUID categoryId,
        SearchVisibility visibility,
        UUID authoredByAccountId
) {
}

package com.taarifu.search.api.dto;

import com.taarifu.search.domain.model.enums.SearchEntityType;

import java.util.UUID;

/**
 * One ranked discovery result returned by {@code GET /search} (ADR-0017 §4).
 *
 * <p>Responsibility: the lean, public view of a matched {@link com.taarifu.search.domain.model.SearchDocument}
 * the citizen/staff client renders in a result list — a typed discriminator, the source aggregate's public id
 * (to re-read the full record from its owning module), a display title, one locale-resolved snippet, the
 * area/category ids (for "filter by this"), and the FTS relevance rank. The full record is intentionally NOT
 * inlined: the client fetches it from the owner by {@link #entityPublicId} when the result is tapped (PRD §15
 * lean payloads; the owner re-applies its own fine-grained visibility on that read).</p>
 *
 * <p><b>🔒 No PII, no internal ids</b> (PRD §18): only public display fields + opaque UUIDs. The document's
 * {@code authoredByAccountId} and {@code visibility} are deliberately absent — they are internal gating state,
 * never shown to a reader.</p>
 *
 * @param entityType     the kind of matched entity (the client groups/badges results by this).
 * @param entityPublicId the source aggregate's public id — the client's re-read key.
 * @param title          the display label.
 * @param snippet        the snippet resolved to the caller's locale (SW default, EN fallback), or {@code null}.
 * @param areaId         the matched row's area public id, or {@code null}.
 * @param categoryId     the matched row's category public id, or {@code null}.
 * @param rank           the FTS relevance score ({@code ts_rank}); higher is more relevant. Results are ordered
 *                       by this descending so the client may show it or ignore it.
 */
public record SearchResultDto(
        SearchEntityType entityType,
        UUID entityPublicId,
        String title,
        String snippet,
        UUID areaId,
        UUID categoryId,
        double rank
) {
}

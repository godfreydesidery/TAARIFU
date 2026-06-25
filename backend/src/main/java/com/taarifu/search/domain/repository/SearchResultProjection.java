package com.taarifu.search.domain.repository;

import java.util.UUID;

/**
 * Spring Data <b>interface projection</b> for one FTS hit returned by the ranked native search query
 * (ADR-0017 §4). It carries exactly the columns {@code GET /search} renders plus the computed {@code ts_rank}
 * — no entity is materialised, so the read stays lean (PRD §15).
 *
 * <p>Responsibility: shape the native query's selected columns into a typed row the mapper turns into a
 * {@link com.taarifu.search.api.dto.SearchResultDto}. The accessor names must match the query's SELECT aliases
 * (snake_case columns are exposed by Spring Data as camelCase getters; the explicit aliases below keep that
 * mapping unambiguous).</p>
 *
 * <p>WHY an interface projection (not the entity / a class DTO): the query computes a {@code rank} that is not a
 * column on {@link com.taarifu.search.domain.model.SearchDocument}, and selecting only the needed columns avoids
 * loading the (unmapped) {@code tsvector} and the internal {@code authored_by_account_id}/{@code visibility}
 * gating fields into the result — they never leave the query.</p>
 */
public interface SearchResultProjection {

    /** @return the {@code entity_type} enum name (mapped back to {@code SearchEntityType} by the mapper). */
    String getEntityType();

    /** @return the source aggregate's public id (the client's re-read key). */
    UUID getEntityPublicId();

    /** @return the display title. */
    String getTitle();

    /** @return the localised Swahili snippet, or {@code null}. */
    String getSnippetSw();

    /** @return the localised English snippet, or {@code null}. */
    String getSnippetEn();

    /** @return the area public id, or {@code null}. */
    UUID getAreaId();

    /** @return the category public id, or {@code null}. */
    UUID getCategoryId();

    /** @return the FTS relevance score ({@code ts_rank}); higher is more relevant. */
    double getRank();
}

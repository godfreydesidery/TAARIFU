package com.taarifu.geography.domain.repository.projection;

import java.util.UUID;

/**
 * Spring Data <b>interface projection</b> for a ward plus its denormalised council and district names,
 * resolved through the closure table in a single query (ARCHITECTURE.md §4.3).
 *
 * <p>Responsibility: lets the ward listing / search repository queries return exactly the columns the
 * {@link com.taarifu.geography.api.dto.WardSummaryDto manual ward-picker} needs — the ward's public id,
 * code and name, and the names of its Council/LGA and District ancestors — <b>without an N+1</b> per-row
 * ancestor lookup and without loading whole {@link com.taarifu.geography.domain.model.Location} graphs.</p>
 *
 * <p>WHY an interface projection (not the entity, nor a constructor/DTO projection): the council and
 * district names come from <i>joined</i> closure rows, not the ward entity itself, so a plain entity
 * read cannot carry them; an interface projection keeps the query a single round-trip and the mapper
 * trivial, while leaving the projection inside the domain layer (the DTO at the boundary is built by the
 * geography mapper, so entities/projections never leak past the module — CLAUDE.md §8).</p>
 *
 * <p>{@code councilName}/{@code districtName} are nullable: a seed chain missing the COUNCIL or DISTRICT
 * ancestor (tolerated during incremental imports, EI-14) simply yields {@code null} rather than dropping
 * the ward — the picker still shows the ward by name/code.</p>
 */
public interface WardSummaryProjection {

    /** @return the ward's public id (the value clients pass to pin a location, ADR-0006). */
    UUID getWardPublicId();

    /** @return the official ward code. */
    String getCode();

    /** @return the ward display name (e.g. "Mengwe"). */
    String getName();

    /** @return the parent Council/LGA (Halmashauri) name, or {@code null} if no council ancestor. */
    String getCouncilName();

    /** @return the District (Wilaya) ancestor name, or {@code null} if no district ancestor. */
    String getDistrictName();
}

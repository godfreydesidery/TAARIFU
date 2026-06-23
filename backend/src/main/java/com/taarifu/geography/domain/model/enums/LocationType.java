package com.taarifu.geography.domain.model.enums;

/**
 * The level of an administrative {@code Location} in Tanzania's civic hierarchy (PRD §9.0 D6/D14,
 * ARCHITECTURE.md §4.3).
 *
 * <p>Responsibility: discriminates the single {@code location} table by level, so the whole
 * administrative chain lives in one queryable hierarchy (closure table) instead of a denormalised
 * per-level mirror (the legacy {@code Area} mistake).</p>
 *
 * <p>Swahili civic terms are preserved as the real names (CLAUDE.md §8, ADR-0010). The chain is:
 * {@code REGION (Mkoa) → DISTRICT (Wilaya) → COUNCIL (Halmashauri/LGA) → [DIVISION (Tarafa), optional]
 * → WARD (Kata) → VILLAGE (Kijiji) / MTAA → HAMLET (Kitongoji)}.</p>
 *
 * <p>WHY {@code COUNCIL} is a first-class level (added per D14): services and officials sit at the
 * Council/LGA, so report routing and official scoping need it. WHY {@code WARD} matters most: it is
 * the <b>minimum pin granularity</b> — enough to derive councillor + constituency + report routing
 * (PRD §9.0).</p>
 */
public enum LocationType {

    /** Mkoa — top-level region. */
    REGION,

    /** Wilaya — district within a region. */
    DISTRICT,

    /** Halmashauri / LGA — local government authority where services and officials sit (D14). */
    COUNCIL,

    /** Tarafa — optional division between council and ward. */
    DIVISION,

    /** Kata — ward; the minimum pin granularity for civic routing (PRD §9.0). */
    WARD,

    /** Kijiji — village (rural). */
    VILLAGE,

    /** Mtaa — street/neighbourhood (urban), peer of {@link #VILLAGE}. */
    MTAA,

    /** Kitongoji — hamlet; the finest administrative unit. */
    HAMLET
}

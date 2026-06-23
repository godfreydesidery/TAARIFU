package com.taarifu.institutions.domain.model.enums;

/**
 * The kind of elected/appointed leader a {@link com.taarifu.institutions.domain.model.Representative}
 * is (PRD §9.1, §22.6, D3).
 *
 * <p>Responsibility: drives both the "find my representatives" fan-out (a citizen's ward resolves to an
 * MP via Ward→Constituency, a Councillor via the ward, and a ward executive officer) and the
 * profile/search facets. WHY launch covers full local government — MPs, Councillors, and ward/village
 * executive officers — not just MPs (D3, PRD §6 AC4): "find my rep" is only useful if it returns the
 * leaders a citizen actually deals with day to day.</p>
 */
public enum RepresentativeType {

    /** Member of Parliament (Mbunge) — elected to, or seated in, the legislature. */
    MP,

    /** Councillor (Diwani) — ward-level elected council representative. */
    COUNCILLOR,

    /** Ward/village executive officer — appointed local-government administrator. */
    WARD_EXEC
}

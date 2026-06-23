package com.taarifu.responders.domain.model.enums;

/**
 * How a {@link com.taarifu.responders.domain.model.Responder}'s geographic coverage is expressed
 * (PRD §24.1, §24.5 — coverage "{areas | nationwide}").
 *
 * <p>Responsibility: discriminates between a responder that operates everywhere ({@link #NATIONWIDE} —
 * e.g. a national telecom or bank) and one scoped to a set of areas ({@link #AREAS} — e.g. a regional
 * TANESCO office). Routing (§24.2) narrows by area only for {@link #AREAS} responders; a
 * {@link #NATIONWIDE} responder matches any area.</p>
 *
 * <p>WHY a discriminator rather than "empty area set means nationwide": an empty area list is
 * ambiguous (mis-seeded vs. deliberately nationwide). An explicit flag makes "covers everywhere" an
 * intentional, auditable statement — important because it widens who can receive a citizen's report
 * (PDPA data-minimisation context, §24.4).</p>
 */
public enum CoverageType {

    /** Coverage is the explicit set of area ids in {@code Responder.coverageAreaIds} (geography ward/region ids). */
    AREAS,

    /** Coverage is the whole country; the area-id set is ignored for matching (PRD §24.1). */
    NATIONWIDE
}

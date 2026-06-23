package com.taarifu.responders.domain.model.enums;

/**
 * The functional kind of a {@link com.taarifu.responders.domain.model.Responder} capability (PRD §24.1).
 *
 * <p>Responsibility: the value a {@link com.taarifu.responders.domain.model.RoutingRule} resolves a
 * category to (PRD §24.2 — "maps {@code (category[, sub])} → responderType/sector"). It mirrors
 * {@link OrganisationType} because a responder capability inherits the nature of its owning
 * organisation, but it is modelled separately so the <i>routing</i> taxonomy can evolve independently
 * of the <i>organisation</i> taxonomy (e.g. a single government agency could one day expose more than
 * one responder kind).</p>
 *
 * <p>WHY this exists alongside {@link OrganisationType}: §24.5 lists {@code responderType} on
 * {@code Responder} explicitly (not only on the org). Keeping the routing target a first-class enum on
 * the responder avoids reaching back into the organisation for every routing decision and keeps the
 * routing rule self-contained.</p>
 */
public enum ResponderType {

    /** Government ministry/agency/authority responder (generalises the legacy Area Official, §24.1). */
    GOVERNMENT_AGENCY,

    /** Parastatal responder (TANESCO, DAWASA, water authorities) — onboarded first (D20). */
    PARASTATAL,

    /** Private-company responder (contractors and others); Phase 2 (§24.6). */
    PRIVATE_COMPANY,

    /** Utility responder (electricity/water/sanitation) — onboarded first (D20). */
    UTILITY,

    /** Bank responder — citizen-selected at report time; Phase 2 (§24.2). */
    BANK,

    /** Telecom responder — citizen-selected at report time; Phase 2 (§24.2). */
    TELECOM,

    /** Civic-society-organisation responder. */
    CIVIC_ORG
}

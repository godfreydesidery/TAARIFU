package com.taarifu.reporting.domain.model.enums;

/**
 * The administrative/oversight level a category's reports are routed to by default (PRD Appendix D.1).
 *
 * <p>Responsibility: an <b>abstraction over agencies</b> — a routing token, not a hardcoded agency name
 * (Appendix D.1 note). Each token maps, per area, to one or more onboarded responder scopes; where a
 * sector/utility is not yet live in a region, the routing engine falls back up the chain to the Council
 * equivalent and flags Admin (Appendix D.3 staged onboarding). The default token lives on
 * {@code IssueCategory.defaultRoutingLevel}.</p>
 *
 * <p>WHY tokens rather than agency FKs: agencies onboard region-by-region (D-Q5); binding the taxonomy to
 * concrete agencies would make the seed taxonomy un-launchable before every agency is live. The token is
 * stable; the per-area token→scope mapping is the responders increment's concern (DEFERRED here).</p>
 */
public enum RoutingLevel {

    /** Ward (Kata) office — Ward Executive Officer / Councillor follow-up. */
    WARD,

    /** Mtaa / Village (Kijiji) — Village/Mtaa Executive Officer. */
    MTAA_VILLAGE,

    /** Council / LGA (Halmashauri) sector department — e.g. Council Water Engineer. */
    COUNCIL,

    /** District administration — department head / DAS office. */
    DISTRICT,

    /** Regional secretariat (RAS) — regional sector officer. */
    REGION,

    /** National agency / parastatal scoped to an area — e.g. TANESCO, DAWASA/RUWASA, TARURA/TANROADS, NEMC. */
    SECTOR_UTILITY,

    /** Independent oversight body — e.g. PCCB/TAKUKURU (corruption), Police/TPF, GBV desk, CHRAGG. */
    OVERSIGHT
}

package com.taarifu.responders.domain.model.enums;

/**
 * The kind of body an {@link com.taarifu.responders.domain.model.Organisation} is (PRD §24.1, D20).
 *
 * <p>Responsibility: classifies a responder organisation so routing (PRD §24.2), phased onboarding
 * (D20: government + parastatals/utilities first; banks/telecoms next), and PDPA data-sharing rules
 * (§24.4 — a private company requires a consent basis a government agency does not) can branch on a
 * single, stable discriminator.</p>
 *
 * <p>WHY a closed enum (not free text): the legacy code's untyped "organisation kind" made routing and
 * phasing ad-hoc; a closed set keeps the discriminator reviewable and lets the DB enforce it with a
 * {@code CHECK} (defense in depth, ARCHITECTURE.md §4.3). Values are append-only — never repurpose a
 * value's meaning, as routing rules and onboarding gates depend on it.</p>
 */
public enum OrganisationType {

    /** A government ministry/agency/authority (generalises the legacy Area Official's employer, §24.1). */
    GOVERNMENT_AGENCY,

    /** A parastatal — e.g. TANESCO, DAWASA, regional water authorities (onboarded with utilities, D20). */
    PARASTATAL,

    /** A private company (banks, telecoms, contractors) — a paying B2B tier, Phase 2 (§24.4, §24.6). */
    PRIVATE_COMPANY,

    /** A utility provider (electricity/water/sanitation) — onboarded first alongside parastatals (D20). */
    UTILITY,

    /** A bank (CRDB, NMB, …) — citizen-selected at report time; Phase 2 (§24.2). */
    BANK,

    /** A telecom (network/airtime) — citizen-selected at report time; Phase 2 (§24.2). */
    TELECOM,

    /** A civic-society organisation (CSO/NGO) acting as a responder. */
    CIVIC_ORG
}

package com.taarifu.privacy.domain.model.enums;

/**
 * The catalogue of <b>lawful-basis purposes</b> a citizen may grant or withdraw consent for
 * (PRD §18 PDPA 2022/2023, UC-A16, US-0.7 {@code consent_state}; ADR-0016 §2).
 *
 * <p>Responsibility: enumerates each distinct processing purpose Taarifu must capture explicit consent
 * for under the Tanzania Personal Data Protection Act. Each value is a stable machine token persisted on
 * {@link com.taarifu.privacy.domain.model.Consent}; values are <b>append-only</b> — never repurpose an
 * existing purpose's meaning (a stored grant must keep meaning what it meant when given). No value here is
 * PII; the purpose is metadata about a decision, not the decision-maker.</p>
 *
 * <p>WHY a closed enum (not a free-form string): the purposes are a governed list reviewed with Legal; a
 * typo'd or invented purpose would be an un-auditable consent basis. Adding a purpose is a deliberate
 * migration + Legal step.</p>
 */
public enum ConsentPurpose {

    /**
     * Sharing a citizen's report (which may carry PII) with a <b>private</b> responder/company (bank,
     * telecom). PRD §24/§25.1 gates this on an explicit data-sharing basis + citizen consent — this is the
     * purpose that records that consent. Without an active grant here, a private-responder share is denied.
     */
    DATA_SHARING_PRIVATE_RESPONDER,

    /**
     * Behavioural / engagement analytics beyond the minimum operational events (PRD §28). An opted-out
     * citizen still emits the operational minimum (e.g. {@code report_routed}) but is excluded from
     * behavioural analytics — this purpose carries that opt-in/opt-out.
     */
    BEHAVIOURAL_ANALYTICS,

    /** Marketing / non-essential promotional notifications (distinct from operational/civic alerts). */
    MARKETING_NOTIFICATIONS,

    /** Automated profiling / personalisation of content beyond the citizen's explicit follows. */
    PROFILING
}

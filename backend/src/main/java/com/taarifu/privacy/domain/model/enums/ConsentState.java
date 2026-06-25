package com.taarifu.privacy.domain.model.enums;

/**
 * The state of a single consent decision (ADR-0016 §2, PRD §18 PDPA).
 *
 * <p>Responsibility: distinguishes an active grant from a withdrawal. Because the consent ledger is
 * <b>append-on-change</b> (a withdrawal supersedes — never edits — the prior grant), the <i>current</i>
 * state for a (subject, purpose) is the latest non-superseded {@link com.taarifu.privacy.domain.model.Consent}
 * row's state. Append-only token; never repurpose.</p>
 */
public enum ConsentState {

    /** The subject has granted consent for the purpose (active lawful basis). */
    GRANTED,

    /** The subject has withdrawn a previously granted consent (PDPA right to withdraw). */
    WITHDRAWN
}

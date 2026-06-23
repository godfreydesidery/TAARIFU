package com.taarifu.institutions.domain.model.enums;

/**
 * Lifecycle of a {@link com.taarifu.institutions.domain.model.Representative} record, independent of the
 * underlying account/citizen role (PRD §6.3 "Role lifecycle", §22.7 lifecycles; UC-A31, UC-C08).
 *
 * <p>Responsibility: tracks a representative from claim through sitting to term-end. WHY this is on the
 * {@code Representative} record and not the account: roles are <b>additive on one account</b> (§6.4) —
 * when a term ends the person stays a citizen with the same account; only this record transitions to
 * {@link #FORMER}. WHY {@code FORMER} is retained, never deleted: the civic/accountability record (who
 * represented this constituency, when) must survive term-end and remain searchable, badged as
 * historical (PRD §22.6, §6.3). Re-election re-activates a record on the same account.</p>
 *
 * <p>Integrity rule (the load-bearing one this module enforces): <b>one {@code SITTING} MP per
 * constituency</b> at a time. {@code PENDING_VERIFICATION} and {@code FORMER} reps do <i>not</i> consume
 * that slot, so a successor can be onboarded as {@code PENDING_VERIFICATION} before the incumbent is
 * retired without tripping the invariant.</p>
 */
public enum RepresentativeStatus {

    /** Claim submitted/linked but not yet verified against the official list (UC-A22, D-Q2). */
    PENDING_VERIFICATION,

    /** Verified and currently serving — the live representative for their seat. */
    SITTING,

    /** Term ended (or seat vacated); record + history retained, badged historical (UC-A31). */
    FORMER
}

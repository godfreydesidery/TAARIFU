package com.taarifu.tokens.domain.model.enums;

/**
 * The kind of movement recorded by an append-only {@link com.taarifu.tokens.domain.model.TokenTransaction}
 * (PRD §23.1, §23.4).
 *
 * <p>Responsibility: classifies every ledger entry. The ledger is the <b>source of truth</b> for balances;
 * the sign each type contributes is fixed here so a balance is always reconstructable by replaying the
 * log (PRD §23.1 — "balance = derived/cached").</p>
 *
 * <p>Sign convention (enforced in the metering/wallet services, not just documented):</p>
 * <ul>
 *   <li>{@link #GRANT}, {@link #EARN}, {@link #PURCHASE}, {@link #REFUND} — <b>credit</b> (+amount).</li>
 *   <li>{@link #SPEND}, {@link #EXPIRE} — <b>debit</b> (−amount).</li>
 *   <li>{@link #ADJUST} — admin correction, may be either sign (audited).</li>
 * </ul>
 *
 * <p>WHY append-only with an explicit type (never an in-place balance update): an immutable typed log
 * prevents silent balance tampering, gives a full audit trail, and makes double-credit detectable — every
 * entry carries an idempotency key so a replayed grant/earn/spend/purchase is a no-op (PRD §23.5
 * anti-farming, idempotent ledger).</p>
 */
public enum TokenTransactionType {

    /** Platform-issued credit (signup starter balance, periodic allowance top-up). Credit. */
    GRANT,

    /** Reward credited for validated good civic behaviour (PRD §23.3). Credit; capped per behaviour. */
    EARN,

    /** Debit when a metered action is paid for with tokens (after free quota is exhausted). Debit. */
    SPEND,

    /** Credit from a completed token purchase (Phase 2, mobile money/card). Credit. */
    PURCHASE,

    /** Credit reversing a prior spend/purchase (admin or failed-payment reconciliation). Credit. */
    REFUND,

    /** Debit when time-limited tokens expire. Debit. */
    EXPIRE,

    /** Admin correction (either sign); always audited with a reason (PRD §23.3, §23.5). */
    ADJUST
}

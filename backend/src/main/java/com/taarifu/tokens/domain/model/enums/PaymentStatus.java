package com.taarifu.tokens.domain.model.enums;

/**
 * Lifecycle of a Phase 2 token {@link com.taarifu.tokens.domain.model.Payment} (PRD §23.4, §23.5).
 *
 * <p>Responsibility: the state machine for a purchase. Tokens are credited to the wallet (a {@code
 * PURCHASE} ledger entry) <b>only</b> on transition to {@link #PAID}, and that crediting is idempotent and
 * reconciled against the provider — never on the unverified callback alone (PRD §23.5 anti-fraud:
 * webhook verification + idempotency + reconciliation).</p>
 */
public enum PaymentStatus {

    /** Initiated; awaiting provider settlement (STK push pending). No tokens credited yet. */
    PENDING,

    /** Settled and reconciled; the corresponding {@code PURCHASE} credit is posted exactly once. */
    PAID,

    /** Provider declined/timed out; no credit posted. */
    FAILED,

    /** A prior {@link #PAID} purchase was refunded; a reversing {@code REFUND} entry is posted. */
    REFUNDED
}

package com.taarifu.payments.domain.model.enums;

/**
 * The lifecycle state of a {@link com.taarifu.payments.domain.model.TopUp} money-movement attempt
 * (ADR-0015; PRD §23.5).
 *
 * <p>Responsibility: a small, explicit state machine for one wallet top-up. Tokens are credited to the
 * buyer's wallet <b>only</b> on the transition into {@link #SUCCEEDED}, and that credit is posted exactly
 * once and reconciled against the provider — never on the raw callback (PRD §23.5). {@link #FAILED} and
 * {@link #VOIDED} are terminal; {@link #REFUNDED} is the terminal state of a settled-then-reversed top-up.</p>
 *
 * <p>Legal transitions (enforced in {@code TopUpService}/{@code ReconciliationService}/{@code RefundService}):
 * {@code INITIATED → PENDING}, {@code INITIATED|PENDING → SUCCEEDED}, {@code INITIATED|PENDING → FAILED},
 * {@code INITIATED|PENDING → VOIDED} (admin cancellation of an un-settled attempt), and
 * {@code SUCCEEDED → REFUNDED} (reverse a settled top-up). A transition out of an already-reversed/failed
 * state is rejected — a duplicate/out-of-order callback on an already-terminal row is a no-op (idempotent
 * reconciliation, PRD §23.5).</p>
 *
 * <p><b>WHY {@link #SUCCEEDED} is no longer treated as immutable-terminal:</b> a settled top-up can still be
 * reversed (refund/chargeback, ADR-0015 revisit trigger (b)). It is therefore <b>credit-terminal</b> (no
 * further callback may re-credit it — {@code isTerminal()} still short-circuits a redelivered settlement
 * callback) but <b>refund-eligible</b> (only the explicit, audited admin {@code RefundService} may move it on
 * to {@link #REFUNDED}). {@link #isRefundable()} captures that one allowed onward edge.</p>
 */
public enum TopUpStatus {

    /** Created locally; a collection (STK push) has been requested but not yet acknowledged. */
    INITIATED,

    /** The provider accepted the collection; awaiting the citizen's handset approval / settlement. */
    PENDING,

    /**
     * Settlement confirmed against the provider; the wallet has been (or is being) credited once.
     * Credit-terminal (no callback may re-credit) but refund-eligible (an admin may reverse it to
     * {@link #REFUNDED}).
     */
    SUCCEEDED,

    /** The collection failed, was rejected, timed out, or was cancelled. No credit. Terminal. */
    FAILED,

    /**
     * An un-settled attempt (INITIATED/PENDING) cancelled by an administrator — the collection never settled,
     * so there is no credit to reverse. Terminal (ADR-0015 addendum: REFUND/VOID support).
     */
    VOIDED,

    /**
     * A previously-SUCCEEDED top-up whose convenience-token credit has been reversed (refund/chargeback). The
     * reversal removes only the purchased convenience tokens — never democratic weight (D18). Terminal
     * (ADR-0015 addendum: REFUND/VOID support).
     */
    REFUNDED
}

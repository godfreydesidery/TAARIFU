package com.taarifu.payments.domain.model.enums;

/**
 * The lifecycle state of a {@link com.taarifu.payments.domain.model.TopUp} money-movement attempt
 * (ADR-0015; PRD §23.5).
 *
 * <p>Responsibility: a small, explicit state machine for one wallet top-up. Tokens are credited to the
 * buyer's wallet <b>only</b> on the transition into {@link #SUCCEEDED}, and that credit is posted exactly
 * once and reconciled against the provider — never on the raw callback (PRD §23.5). {@link #SUCCEEDED} and
 * {@link #FAILED} are terminal.</p>
 *
 * <p>Legal transitions (enforced in {@code TopUpService}/{@code ReconciliationService}):
 * {@code INITIATED → PENDING}, {@code INITIATED|PENDING → SUCCEEDED}, {@code INITIATED|PENDING → FAILED}.
 * A transition out of a terminal state is rejected — a duplicate/out-of-order callback on an already
 * SUCCEEDED row is a no-op (idempotent reconciliation).</p>
 */
public enum TopUpStatus {

    /** Created locally; a collection (STK push) has been requested but not yet acknowledged. */
    INITIATED,

    /** The provider accepted the collection; awaiting the citizen's handset approval / settlement. */
    PENDING,

    /** Settlement confirmed against the provider; the wallet has been (or is being) credited once. Terminal. */
    SUCCEEDED,

    /** The collection failed, was rejected, timed out, or was cancelled. No credit. Terminal. */
    FAILED
}

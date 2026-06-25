package com.taarifu.payments.domain.port;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;

import java.util.UUID;

/**
 * Outbound port the payments module uses to <b>reverse</b> a previously-credited top-up from the buyer's
 * token wallet when the underlying payment is refunded/charged-back (ADR-0015 addendum: REFUND/VOID;
 * PRD §23.5, §23.6).
 *
 * <p>Responsibility: the mirror of {@link WalletCreditPort} — a single, narrow seam: "remove N convenience
 * tokens that a settled top-up added, idempotently, as a refund reversal". It exists so payments never
 * reaches into the tokens module's tables (ADR-0013): the production adapter delegates to the published
 * {@code tokens.api} refund method (a {@code REFUND}-type ledger entry that reverses the prior
 * {@code PURCHASE} credit); a logging stub adapter lets a tokens-less dev/CI context boot and the refund flow
 * be tested without the tokens module wired.</p>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> this port can do <b>exactly one</b> thing —
 * reverse convenience tokens a top-up added. It exposes <b>no</b> way to grant or revoke a role, a vote, a
 * signature, a rating, a poll outcome, routing/SLA/priority, or a verification status, and it never returns
 * or consults a balance for any authorization. Just as a purchase never buys democratic weight, a refund
 * never touches democratic weight — it only undoes the convenience top-up. The fence is the shape of this
 * interface: the reverse-top-up door exists; the democratic-weight door does not.</p>
 *
 * <p><b>CENTRAL NEED (ADR-0015 addendum):</b> the {@code tokens-api} adapter requires a published
 * {@code tokens.api.TokenLedgerApi.refund(WalletOwnerType, UUID, long, String)} method (a fence-safe
 * {@code REFUND}-type reversal of a prior {@code PURCHASE}, idempotent on the reversal reference). Until that
 * lands the logging stub is the active adapter and the refund flow is exercised end-to-end against this port
 * in tests.</p>
 */
public interface WalletReversalPort {

    /**
     * Reverses {@code tokenAmount} convenience tokens from the owner's wallet as a refund of a prior settled
     * top-up, <b>idempotently</b>.
     *
     * <p>Idempotent on {@code idempotencyKey}: a retried refund (or a redelivered provider refund callback)
     * reverses the wallet exactly once — a replay reverses nothing (the same anti-double-credit discipline
     * the ledger enforces for credits, applied in reverse; PRD §23.5).</p>
     *
     * @param ownerType      which wallet class to debit (USER/ORGANIZATION).
     * @param ownerId        the wallet owner's public id (opaque UUID).
     * @param tokenAmount    positive number of tokens to reverse (the amount the top-up originally credited).
     * @param idempotencyKey unique key for this reversal (the top-up's {@code reversal_event_id}).
     * @return {@code true} if a reversal was posted by this call; {@code false} if it was an idempotent replay
     *         (already reversed under this key) — both outcomes leave the wallet correctly reversed once.
     */
    boolean reverseTopUp(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount, String idempotencyKey);
}

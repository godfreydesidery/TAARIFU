package com.taarifu.payments.domain.port;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;

import java.util.UUID;

/**
 * Outbound port the payments module uses to credit a settled top-up to the buyer's <b>token wallet</b>
 * (ADR-0015; PRD §23.4/§23.6).
 *
 * <p>Responsibility: a single, narrow seam — "credit N tokens to this owner's wallet as a purchase top-up,
 * idempotently". It exists so payments never reaches into the tokens module's tables (ADR-0013): the
 * production adapter ({@code TokensApiWalletCreditAdapter}, the wired default) makes a direct typed call to
 * the published {@code tokens.api.TokenLedgerApi.topUp(...)}; a logging stub adapter lets a stripped
 * dev/CI context boot and the flow be tested without the tokens module wired.</p>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> this port can do <b>exactly one</b> thing —
 * add convenience tokens to a wallet. It deliberately exposes <b>no</b> way to grant a role, a vote, a
 * signature, a rating, a poll outcome, routing/SLA/priority, or a verification status, and it never returns
 * or consults a balance. A purchased token buys convenience/reach only, never democratic weight. The fence
 * is the shape of this interface: the top-up door exists; the democratic-weight door does not.</p>
 *
 * <p>The {@code tokens-api} adapter delegates to the fence-safe
 * {@code tokens.api.TokenLedgerApi.topUp(WalletOwnerType, UUID, long tokenAmount, String paymentReference)}
 * credit method (a {@code PURCHASE}-type idempotent credit) — that method has landed and the typed adapter
 * is the wired default; the logging stub remains available for a tokens-less dev/CI boot.</p>
 */
public interface WalletCreditPort {

    /**
     * Credits {@code tokenAmount} convenience tokens to the owner's wallet as a purchase top-up,
     * <b>idempotently</b>.
     *
     * <p>Idempotent on {@code idempotencyKey}: a redelivered SUCCEEDED callback (at-least-once webhook
     * delivery) credits the wallet exactly once — a replay credits nothing (PRD §23.5 anti-fraud).</p>
     *
     * @param ownerType      which wallet class to credit (USER/ORGANIZATION).
     * @param ownerId        the wallet owner's public id (opaque UUID).
     * @param tokenAmount    positive number of tokens to credit.
     * @param idempotencyKey unique key for this credit (the top-up's {@code credit_event_id}).
     * @return {@code true} if a credit was posted by this call; {@code false} if it was an idempotent replay
     *         (already credited under this key) — both outcomes leave the wallet correctly credited once.
     */
    boolean creditPurchase(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount, String idempotencyKey);
}

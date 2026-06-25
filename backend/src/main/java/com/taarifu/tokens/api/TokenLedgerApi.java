package com.taarifu.tokens.api;

import com.taarifu.tokens.application.service.SpendOutcome;
import com.taarifu.tokens.domain.model.enums.RewardBehaviour;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;

import java.util.UUID;

/**
 * The tokens module's <b>public, in-process API</b> for other modules to meter actions and credit rewards
 * (ARCHITECTURE.md §3.2 — modules talk only through each other's published {@code api} package; M17, D18).
 *
 * <p>Responsibility: the single contract a feature module (reporting/engagement/communications/responders)
 * depends on to (a) charge a metered convenience/volume/reach action, and (b) credit a validated good-civic
 * behaviour — without importing tokens' internals or touching its tables. Callers reference owners by public
 * {@code UUID} only.</p>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> there is deliberately <b>no</b> method here
 * that a binding democratic action (sign petition / rate rep / binding poll) could call to consult a balance
 * or gate on tokens. {@link #meter} additionally hard-rejects reserved binding action codes. A binding
 * endpoint authorises on <i>tier + electoral scope + one-per-person only</i> and must never reach into this
 * API for a balance. This shape is the fence expressed as an API: the convenience-metering door exists, the
 * democratic-weight door does not.</p>
 */
public interface TokenLedgerApi {

    /**
     * Meters one attempt at a convenience/volume/reach action: consumes the recurring free quota first, then
     * tokens (PRD §23.2). Idempotent on {@code idempotencyKey}.
     *
     * @param ownerType      owner class (USER/ORGANIZATION).
     * @param ownerId        owner public id.
     * @param actionCode     the metered action (must NOT be a binding democratic action — rejected if so).
     * @param roleName       the role the owner acts in (drives the policy), or {@code null}.
     * @param refEntityType  type of the entity the action targets (e.g. {@code REPORT}), or {@code null}.
     * @param refEntityId    public id of that entity, or {@code null}.
     * @param idempotencyKey unique key for this metered attempt.
     * @return the {@link SpendOutcome} (free-quota / tokens / insufficient).
     */
    SpendOutcome meter(WalletOwnerType ownerType, UUID ownerId, String actionCode, String roleName,
                       String refEntityType, UUID refEntityId, String idempotencyKey);

    /**
     * Credits a settled <b>purchase top-up</b> (mobile-money/card) of spendable convenience tokens to an
     * owner's wallet, <b>idempotently</b> on {@code paymentReference} (PRD §23.4/§23.6; ADR-0015 §4). This is
     * the typed cross-module seam the {@code payments} module calls on a SUCCEEDED settlement (replacing the
     * temporary reflective bridge); it appends a {@code PURCHASE}-kind append-only ledger entry and advances
     * the wallet's cached balance in the same transaction.
     *
     * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> a top-up adds <b>only</b> spendable
     * convenience tokens — it MUST NOT grant a role, a vote, a signature, a rating, a poll outcome,
     * routing/SLA/priority, or a verification status. There is deliberately <b>no balance returned</b> and no
     * path from this method to any democratic-weight effect, and — like the rest of this API — no binding
     * democratic action may consult a balance through it. A purchased token buys convenience/reach only,
     * never democratic weight. This method is the convenience-credit door; the democratic-weight door does
     * not exist on this contract by design.</p>
     *
     * <p><b>Idempotency:</b> {@code paymentReference} is the credit's unique key (the payments
     * {@code credit_event_id}). A redelivered/out-of-order mobile-money webhook or a retried credit under the
     * same reference credits the wallet <b>exactly once</b> — a replay credits nothing (no double-credit,
     * PRD §23.5 anti-fraud).</p>
     *
     * @param ownerType        owner class (USER/ORGANIZATION).
     * @param accountPublicId  the wallet owner's public id (opaque UUID; never a national/voter ID — no PII).
     * @param amount           positive number of tokens purchased.
     * @param paymentReference the originating payment's settlement/credit reference; doubles as the
     *                         idempotency key (a machine reference, never PII).
     * @return {@code true} (a credit is posted, or idempotently confirmed already-posted under this
     *         reference). The wallet is correctly credited exactly once in both cases.
     * @throws com.taarifu.common.error.ApiException if {@code amount <= 0} (a top-up of zero/negative tokens
     *         is rejected).
     */
    boolean topUp(WalletOwnerType ownerType, UUID accountPublicId, long amount, String paymentReference);

    /**
     * Credits an earned reward for an <b>already-validated</b> civic behaviour, honouring the per-behaviour
     * anti-farming cap and ledger idempotency (PRD §23.3, §23.5).
     *
     * @param ownerType      owner class.
     * @param ownerId        owner public id.
     * @param behaviour      the validated civic behaviour.
     * @param refEntityType  justifying entity type, or {@code null}.
     * @param refEntityId    justifying entity public id, or {@code null}.
     * @param idempotencyKey unique key for this earn (a replay credits nothing).
     * @return {@code true} if a reward was credited; {@code false} if none configured or the cap was reached.
     */
    boolean reward(WalletOwnerType ownerType, UUID ownerId, RewardBehaviour behaviour,
                   String refEntityType, UUID refEntityId, String idempotencyKey);
}

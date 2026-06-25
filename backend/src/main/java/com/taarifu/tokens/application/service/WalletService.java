package com.taarifu.tokens.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.tokens.domain.model.TokenReward;
import com.taarifu.tokens.domain.model.TokenTransaction;
import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import com.taarifu.tokens.domain.model.enums.RewardBehaviour;
import com.taarifu.tokens.domain.model.enums.TokenTransactionType;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import com.taarifu.tokens.domain.repository.TokenRewardRepository;
import com.taarifu.tokens.domain.repository.TokenTransactionRepository;
import com.taarifu.tokens.domain.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Application service that owns the <b>wallet lifecycle and the credit side of the ledger</b> — provisioning
 * a wallet, granting a starter/allowance balance, and crediting earned rewards — all <b>idempotently</b>
 * (PRD §23.1, §23.3, §23.5; M17, D18).
 *
 * <p>Responsibility: every credit appends an immutable {@link TokenTransaction} and updates the wallet's
 * cached balance in the <b>same transaction</b>, so the cache never diverges from the ledger source of
 * truth. Each credit carries an <b>idempotency key</b>; a replay (retried request, duplicate webhook,
 * re-run reward) is detected and returns the original entry — there is no double-credit (PRD §23.5).</p>
 *
 * <p><b>Anti-farming (PRD §23.5):</b> {@link #earn} enforces the per-behaviour {@link TokenReward} cap per
 * period (a self-confirmed loop or repeated trigger cannot mint beyond the cap), and the idempotency key
 * stops exact replays. <i>Validating that the behaviour genuinely occurred</i> is the calling module's job;
 * this service trusts the caller's assertion only up to the cap and ledger idempotency.</p>
 *
 * <p><b>Civic-integrity fence (D18):</b> nothing here ever gates a binding democratic action on balance.
 * Granting/earning tokens is a positive-behaviour incentive; the tokens it produces buy only
 * convenience/reach, never a signature/rating/poll outcome (PRD §23 fence).</p>
 */
@Service
@Transactional
public class WalletService {

    private final WalletRepository wallets;
    private final TokenTransactionRepository ledger;
    private final TokenRewardRepository rewards;
    private final ClockPort clock;

    /**
     * @param wallets wallet persistence.
     * @param ledger  append-only ledger persistence (idempotency + balance derivation).
     * @param rewards reward-config lookup (earn caps).
     * @param clock   injectable time for cap windows (testability).
     */
    public WalletService(WalletRepository wallets,
                         TokenTransactionRepository ledger,
                         TokenRewardRepository rewards,
                         ClockPort clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.rewards = rewards;
        this.clock = clock;
    }

    /**
     * Returns the owner's wallet, creating one on first use (get-or-create).
     *
     * <p>WHY get-or-create rather than provisioning at signup from another module: it keeps the tokens
     * module the sole owner of wallet creation and avoids a cross-module write into our table; any module
     * that needs an owner's wallet simply asks for it by id (ARCHITECTURE.md §3.2). The unique
     * {@code (owner_type, owner_id)} constraint makes a concurrent double-create resolve to one wallet.</p>
     *
     * @param ownerType owner class.
     * @param ownerId   owner public id.
     * @return the (existing or newly created) wallet.
     */
    public Wallet getOrCreateWallet(WalletOwnerType ownerType, UUID ownerId) {
        return wallets.findByOwnerTypeAndOwnerId(ownerType, ownerId)
                .orElseGet(() -> {
                    try {
                        return wallets.save(new Wallet(ownerType, ownerId));
                    } catch (DataIntegrityViolationException raced) {
                        // A concurrent request created it first; the unique constraint protected us.
                        return wallets.findByOwnerTypeAndOwnerId(ownerType, ownerId)
                                .orElseThrow(() -> raced);
                    }
                });
    }

    /**
     * Reads an owner's wallet for a query, or throws not-found if it has never been provisioned.
     *
     * @param ownerType owner class.
     * @param ownerId   owner public id.
     * @return the wallet.
     * @throws ResourceNotFoundException if the owner has no wallet yet.
     */
    @Transactional(readOnly = true)
    public Wallet requireWallet(WalletOwnerType ownerType, UUID ownerId) {
        return wallets.findByOwnerTypeAndOwnerId(ownerType, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("tokens.wallet.notFound", ownerId));
    }

    /**
     * Credits a platform grant (signup starter balance or periodic allowance) idempotently.
     *
     * @param ownerType      owner class.
     * @param ownerId        owner public id.
     * @param amount         positive token amount to grant.
     * @param reason         machine reason (e.g. {@code SIGNUP_GRANT}); never PII.
     * @param idempotencyKey unique key for this grant (a replay is a no-op).
     * @return the resulting (or pre-existing) ledger entry.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code amount <= 0}.
     */
    public TokenTransaction grant(WalletOwnerType ownerType, UUID ownerId, long amount,
                                  String reason, String idempotencyKey) {
        requirePositive(amount);
        return creditIdempotent(ownerType, ownerId, TokenTransactionType.GRANT, amount,
                null, reason, null, null, idempotencyKey, null);
    }

    /**
     * Credits a settled <b>purchase top-up</b> (mobile-money/card) to the owner's wallet, idempotently
     * (PRD §23.4/§23.6; ADR-0015). Appends an append-only {@link TokenTransactionType#PURCHASE} ledger entry
     * and advances the cached balance in the same transaction.
     *
     * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> a purchase top-up adds <b>only</b>
     * spendable convenience tokens. It NEVER grants a role, a vote, a signature, a rating, a poll outcome,
     * routing/SLA/priority, or a verification status, and a binding democratic action must never read a
     * balance: there is deliberately no balance return here and no path from this method to any
     * democratic-weight effect. The credited tokens buy convenience/reach only — never democratic weight.
     * This is the same fence as {@link #grant}/{@link #earn}: a credit is a credit; what it can be spent on
     * is constrained entirely by the metering side, which hard-rejects binding action codes.</p>
     *
     * @param ownerType         owner class (USER/ORGANIZATION).
     * @param ownerId           owner public id (opaque UUID; never a national/voter ID — no PII here).
     * @param amount            positive number of tokens purchased.
     * @param paymentReference  the settlement reference of the originating payment (recorded as the ledger
     *                          {@code reason} for audit; never PII — it is a provider/credit reference id).
     * @param idempotencyKey    unique key for this credit (the top-up's {@code credit_event_id}); a redelivered
     *                          or retried settlement under the same key credits the wallet <b>exactly once</b>.
     * @return the resulting (or pre-existing, on replay) {@link PURCHASE} ledger entry.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code amount <= 0}.
     */
    public TokenTransaction purchaseTopUp(WalletOwnerType ownerType, UUID ownerId, long amount,
                                          String paymentReference, String idempotencyKey) {
        requirePositive(amount);
        // refEntityType=PAYMENT ties the ledger entry back to the originating money-movement by reference
        // only (cross-module, no FK — §3.2); reason carries the settlement reference for audit. Both are
        // machine references, never PII. A blank reference degrades to the bare PURCHASE_TOP_UP reason.
        String reason = (paymentReference == null || paymentReference.isBlank())
                ? "PURCHASE_TOP_UP" : "PURCHASE_TOP_UP:" + paymentReference;
        return creditIdempotent(ownerType, ownerId, TokenTransactionType.PURCHASE, amount,
                "PURCHASE", reason, "PAYMENT", null, idempotencyKey, null);
    }

    /**
     * Credits an earned reward for a validated civic behaviour, honouring the per-behaviour cap.
     *
     * @param ownerType      owner class.
     * @param ownerId        owner public id.
     * @param behaviour      the (already validated) civic behaviour.
     * @param refEntityType  type of the entity that justifies the reward (e.g. {@code REPORT}), or {@code null}.
     * @param refEntityId    public id of that entity, or {@code null}.
     * @param idempotencyKey unique key for this earn (a replay is a no-op).
     * @return the resulting (or pre-existing) ledger entry, or {@code null} if no reward is configured or
     *         the cap is already reached (a no-op — the caller treats this as "earned nothing").
     */
    public TokenTransaction earn(WalletOwnerType ownerType, UUID ownerId, RewardBehaviour behaviour,
                                 String refEntityType, UUID refEntityId, String idempotencyKey) {
        TokenReward reward = rewards.findByBehaviourAndActiveTrue(behaviour).orElse(null);
        if (reward == null || reward.getGrantAmount() <= 0) {
            return null; // No active reward configured for this behaviour → nothing to credit.
        }

        // Idempotency first: a replay of the same earn must never re-credit (and must not consume cap).
        var existing = ledger.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Wallet wallet = getOrCreateWalletForUpdate(ownerType, ownerId);

        // Anti-farming cap: count prior EARN entries for this behaviour in the cap window (PRD §23.5).
        Instant windowStart = capWindowStart(reward.getCapPeriod());
        long earnedInWindow = ledger.countInWindow(wallet.getId(), TokenTransactionType.EARN,
                behaviour.name(), windowStart);
        if (earnedInWindow >= reward.getCapCount()) {
            return null; // Cap reached for this period → no-op (incentive, not a farm).
        }

        return appendCredit(wallet, TokenTransactionType.EARN, reward.getGrantAmount(),
                behaviour.name(), "REWARD:" + behaviour.name(), refEntityType, refEntityId,
                idempotencyKey, ownerId);
    }

    /**
     * Admin adjustment (either direction), fully audited via the ledger reason (PRD §23.3).
     *
     * @param ownerType      owner class.
     * @param ownerId        owner public id.
     * @param signedAmount   positive to credit, negative to debit.
     * @param reason         machine reason for the adjustment; never PII.
     * @param actorPublicId  the admin performing the adjustment (recorded on the entry).
     * @param idempotencyKey unique key for this adjustment.
     * @return the resulting (or pre-existing) ledger entry.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if {@code signedAmount == 0}, or
     *                      {@link ErrorCode#CONFLICT} if a debit would drive the balance negative.
     */
    public TokenTransaction adjust(WalletOwnerType ownerType, UUID ownerId, long signedAmount,
                                   String reason, UUID actorPublicId, String idempotencyKey) {
        if (signedAmount == 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
        var existing = ledger.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        Wallet wallet = getOrCreateWalletForUpdate(ownerType, ownerId);
        long current = currentBalance(wallet);
        long newBalance = current + signedAmount;
        if (newBalance < 0) {
            throw new ApiException(ErrorCode.CONFLICT); // never drive a balance negative
        }
        TokenTransaction tx = TokenTransaction.Builder
                .of(wallet, TokenTransactionType.ADJUST, Math.abs(signedAmount))
                .balanceAfter(newBalance)
                .reason(reason)
                .actionCode("ADJUST")
                .idempotencyKey(idempotencyKey)
                .actor(actorPublicId)
                .build();
        TokenTransaction saved = ledger.save(tx);
        wallet.applyBalance(newBalance);
        wallets.save(wallet);
        return saved;
    }

    /**
     * Derives a wallet's authoritative balance from the ledger (source of truth, PRD §23.1).
     *
     * @param wallet the wallet.
     * @return the running balance from the latest ledger entry, or 0 if the wallet has no entries.
     */
    @Transactional(readOnly = true)
    public long currentBalance(Wallet wallet) {
        return ledger.latestBalanceForWallet(wallet.getId()).orElse(0L);
    }

    // ---- internals ---------------------------------------------------------------------------------

    /** Loads the owner's wallet with a write lock (creating it if absent), for safe credit mutation. */
    private Wallet getOrCreateWalletForUpdate(WalletOwnerType ownerType, UUID ownerId) {
        return wallets.findForUpdate(ownerType, ownerId)
                .orElseGet(() -> {
                    Wallet created = getOrCreateWallet(ownerType, ownerId);
                    // Re-acquire under lock now that the row exists (best-effort; same tx).
                    return wallets.findForUpdate(ownerType, ownerId).orElse(created);
                });
    }

    /** Idempotent credit used by {@link #grant}: returns the existing entry on replay, else appends. */
    private TokenTransaction creditIdempotent(WalletOwnerType ownerType, UUID ownerId,
                                              TokenTransactionType type, long amount, String actionCode,
                                              String reason, String refType, UUID refId,
                                              String idempotencyKey, UUID actorPublicId) {
        var existing = ledger.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        Wallet wallet = getOrCreateWalletForUpdate(ownerType, ownerId);
        return appendCredit(wallet, type, amount, actionCode, reason, refType, refId, idempotencyKey,
                actorPublicId);
    }

    /** Appends a credit entry and advances the cached balance in the same transaction. */
    private TokenTransaction appendCredit(Wallet wallet, TokenTransactionType type, long amount,
                                          String actionCode, String reason, String refType, UUID refId,
                                          String idempotencyKey, UUID actorPublicId) {
        long newBalance = currentBalance(wallet) + amount;
        TokenTransaction tx = TokenTransaction.Builder
                .of(wallet, type, amount)
                .balanceAfter(newBalance)
                .actionCode(actionCode)
                .reason(reason)
                .ref(refType, refId)
                .idempotencyKey(idempotencyKey)
                .actor(actorPublicId)
                .build();
        TokenTransaction saved;
        try {
            saved = ledger.save(tx);
        } catch (DataIntegrityViolationException raced) {
            // A concurrent identical credit won the unique idempotency key; return that one (no double-credit).
            return ledger.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raced);
        }
        wallet.applyBalance(newBalance);
        wallets.save(wallet);
        return saved;
    }

    /** Computes the cap window start for an anti-farming period. */
    private Instant capWindowStart(QuotaPeriod period) {
        return period.windowStart(clock.now());
    }

    /** Guards positive grant/earn amounts. */
    private void requirePositive(long amount) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }
}

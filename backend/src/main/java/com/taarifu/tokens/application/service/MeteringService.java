package com.taarifu.tokens.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.tokens.domain.model.ActionCostPolicy;
import com.taarifu.tokens.domain.model.TokenTransaction;
import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.WalletFreeQuotaState;
import com.taarifu.tokens.domain.model.enums.TokenTransactionType;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import com.taarifu.tokens.domain.repository.ActionCostPolicyRepository;
import com.taarifu.tokens.domain.repository.TokenTransactionRepository;
import com.taarifu.tokens.domain.repository.WalletFreeQuotaStateRepository;
import com.taarifu.tokens.domain.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The metering engine: charges a metered action against the <b>recurring free quota first, then tokens</b>
 * (PRD §23.2; M17, D18).
 *
 * <p>Responsibility: {@link #spend} resolves the active {@link ActionCostPolicy} for an action and role,
 * consumes one free use if the window has allowance left (no token movement, no ledger entry), and only
 * when the free quota is exhausted debits tokens by appending a {@code SPEND} {@link TokenTransaction}.
 * Idempotency, the free-counter window roll-over, and the cached-balance update all happen in one
 * transaction (PRD §23.1/§23.2/§23.5).</p>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23 fence):</b> this metering path must <b>never</b> be
 * invoked for a binding democratic action — signing a petition, rating a representative, or casting a
 * binding poll vote. Those are decided by <i>tier + electoral scope + one-per-person only</i> and must never
 * read a token balance or a quota (PRD §23.5). To make the fence un-bypassable in code (not just in docs),
 * {@link #spend} <b>hard-rejects</b> any reserved binding action code with {@link ErrorCode#FORBIDDEN}; a
 * unit test asserts this and would fail if the guard were removed. Tokens meter only convenience, volume,
 * speed, reach, and commercial features.</p>
 *
 * <p><b>Boost/feature note (PRD §23.5):</b> "boost/feature" action codes are legitimately metered here, but
 * they affect <i>discovery reach only</i> — never a report's official routing/SLA/priority or a petition's
 * signature count. That separation is enforced by the consuming module (reporting/engagement), which must
 * not read this outcome into any official-handling decision.</p>
 */
@Service
@Transactional
public class MeteringService {

    /**
     * Reserved action codes for binding democratic actions that must NEVER be metered (the fence, D18).
     *
     * <p>WHY a hard deny-list in the metering engine itself: defence in depth. The primary control is that
     * binding endpoints simply never call metering; this list guarantees that even a mis-wired caller cannot
     * accidentally turn a democratic act into a token spend. These codes are the canonical names the
     * engagement/accountability modules use for the fenced actions (referenced by value, §3.2).</p>
     */
    static final Set<String> BINDING_ACTION_CODES = Set.of(
            "SIGN_PETITION",   // one person = one signature, never balance-gated (PRD §23 fence)
            "RATE_REP",        // one person = one rating
            "VOTE_BINDING_POLL" // one person = one binding vote
    );

    private final WalletRepository wallets;
    private final ActionCostPolicyRepository policies;
    private final WalletFreeQuotaStateRepository quotaStates;
    private final TokenTransactionRepository ledger;
    private final ClockPort clock;

    /**
     * @param wallets     wallet persistence (locked load for spend).
     * @param policies    cost/quota policy lookup.
     * @param quotaStates per-wallet free-quota counters.
     * @param ledger      append-only ledger (idempotency + balance).
     * @param clock       injectable time for window resolution (testability).
     */
    public MeteringService(WalletRepository wallets,
                           ActionCostPolicyRepository policies,
                           WalletFreeQuotaStateRepository quotaStates,
                           TokenTransactionRepository ledger,
                           ClockPort clock) {
        this.wallets = wallets;
        this.policies = policies;
        this.quotaStates = quotaStates;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Meters one attempt at {@code actionCode} for the wallet owner acting in {@code roleName}.
     *
     * <p>Order of settlement (PRD §23.2 — free path first, never hidden):</p>
     * <ol>
     *   <li><b>Fence:</b> reject reserved binding action codes outright (D18).</li>
     *   <li><b>Idempotency:</b> if {@code idempotencyKey} already produced a SPEND, return that outcome.</li>
     *   <li><b>Policy:</b> resolve the active policy (role-specific, else default). No policy ⇒ the action
     *       is unmetered ⇒ allowed for free.</li>
     *   <li><b>Free quota:</b> roll the window if stale; if free uses remain, consume one (no token move).</li>
     *   <li><b>Tokens:</b> else, if the wallet is active and balance ≥ cost, debit and append a SPEND.</li>
     *   <li><b>Insufficient:</b> else return {@link SpendOutcome.Settlement#INSUFFICIENT} (caller rejects;
     *       the UX offers "wait for refresh" before "buy/earn" — PRD §23.2).</li>
     * </ol>
     *
     * @param ownerType      owner class.
     * @param ownerId        owner public id.
     * @param actionCode     the metered action (must NOT be a binding democratic action).
     * @param roleName       the role the owner is acting in (drives the policy), or {@code null}.
     * @param refEntityType  type of the entity the action targets (for the ledger ref), or {@code null}.
     * @param refEntityId    public id of that entity, or {@code null}.
     * @param idempotencyKey unique key for this metered attempt (a replay returns the original outcome).
     * @return the {@link SpendOutcome}.
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if {@code actionCode} is a fenced binding action.
     */
    public SpendOutcome spend(WalletOwnerType ownerType, UUID ownerId, String actionCode, String roleName,
                              String refEntityType, UUID refEntityId, String idempotencyKey) {
        // (1) THE FENCE: tokens never meter democratic weight. This throw is load-bearing (D18) — a test
        // asserts it fails closed if removed.
        if (actionCode != null && BINDING_ACTION_CODES.contains(actionCode)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }

        // (2) Idempotency: a replayed metered attempt must not double-charge.
        var prior = ledger.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            TokenTransaction existing = prior.get();
            long balance = ledger.latestBalanceForWallet(existing.getWallet().getId()).orElse(0L);
            return new SpendOutcome(SpendOutcome.Settlement.TOKENS, existing.getAmount(),
                    -1, balance, existing.getPublicId());
        }

        // (3) Policy resolution: role-specific first, then default; absent ⇒ unmetered (free).
        ActionCostPolicy policy = resolvePolicy(actionCode, roleName);
        Wallet wallet = lockWallet(ownerType, ownerId);
        long balance = ledger.latestBalanceForWallet(wallet.getId()).orElse(0L);

        if (policy == null) {
            // Unmetered action: always allowed, no charge, no ledger entry (PRD §23.2 "frictionless core").
            return new SpendOutcome(SpendOutcome.Settlement.FREE_QUOTA, 0, Integer.MAX_VALUE, balance, null);
        }

        // (4) Free quota first.
        Instant now = clock.now();
        Instant windowStart = policy.getFreeQuotaPeriod().windowStart(now);
        WalletFreeQuotaState state = getOrCreateQuotaState(wallet, actionCode, windowStart);
        if (!state.getWindowStart().equals(windowStart)) {
            state.rollTo(windowStart); // automatic refresh — the window rolled over (PRD §23.1)
        }
        int freeRemaining = Math.max(0, policy.getFreeQuotaCount() - state.getUsedCount());
        if (freeRemaining > 0) {
            state.incrementUsed();
            quotaStates.save(state);
            return new SpendOutcome(SpendOutcome.Settlement.FREE_QUOTA, 0,
                    freeRemaining - 1, balance, null);
        }

        // (5) Free quota exhausted → pay with tokens, if the wallet is active and can afford it.
        long cost = policy.getTokenCost();
        if (cost <= 0) {
            // Policy with no free quota left but zero cost ⇒ still free (never block a zero-cost action).
            return new SpendOutcome(SpendOutcome.Settlement.FREE_QUOTA, 0, 0, balance, null);
        }
        if (!wallet.isActive() || balance < cost) {
            // (6) Insufficient: do not charge. The free path ("wait for refresh") remains and is never
            // hidden (PRD §23.2); the caller rejects with a transparent message.
            return new SpendOutcome(SpendOutcome.Settlement.INSUFFICIENT, 0, 0, balance, null);
        }

        long newBalance = balance - cost;
        TokenTransaction tx = TokenTransaction.Builder
                .of(wallet, TokenTransactionType.SPEND, cost)
                .balanceAfter(newBalance)
                .actionCode(actionCode)
                .reason("SPEND:" + actionCode)
                .ref(refEntityType, refEntityId)
                .idempotencyKey(idempotencyKey)
                .actor(ownerId)
                .build();
        TokenTransaction saved;
        try {
            saved = ledger.save(tx);
        } catch (DataIntegrityViolationException raced) {
            // Concurrent identical spend won the idempotency key; return its outcome (no double-charge).
            TokenTransaction existing = ledger.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raced);
            long b = ledger.latestBalanceForWallet(existing.getWallet().getId()).orElse(0L);
            return new SpendOutcome(SpendOutcome.Settlement.TOKENS, existing.getAmount(), 0, b,
                    existing.getPublicId());
        }
        wallet.applyBalance(newBalance);
        wallets.save(wallet);
        return new SpendOutcome(SpendOutcome.Settlement.TOKENS, cost, 0, newBalance, saved.getPublicId());
    }

    /**
     * Read-only quote: how the next attempt at {@code actionCode} would be settled, without consuming
     * anything (PRD §23.5 transparency — show cost/free-quota before spending).
     *
     * @param ownerType  owner class.
     * @param ownerId    owner public id.
     * @param actionCode the metered action.
     * @param roleName   the acting role, or {@code null}.
     * @return a non-mutating preview {@link SpendOutcome} ({@code ledgerTransactionId} always {@code null}).
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if {@code actionCode} is a fenced binding action.
     */
    @Transactional(readOnly = true)
    public SpendOutcome quote(WalletOwnerType ownerType, UUID ownerId, String actionCode, String roleName) {
        if (actionCode != null && BINDING_ACTION_CODES.contains(actionCode)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        Wallet wallet = wallets.findByOwnerTypeAndOwnerId(ownerType, ownerId).orElse(null);
        long balance = wallet == null ? 0L : ledger.latestBalanceForWallet(wallet.getId()).orElse(0L);
        ActionCostPolicy policy = resolvePolicy(actionCode, roleName);
        if (policy == null) {
            return new SpendOutcome(SpendOutcome.Settlement.FREE_QUOTA, 0, Integer.MAX_VALUE, balance, null);
        }
        int freeRemaining = policy.getFreeQuotaCount();
        if (wallet != null) {
            Instant windowStart = policy.getFreeQuotaPeriod().windowStart(clock.now());
            WalletFreeQuotaState state = quotaStates.findByWalletAndActionCode(wallet, actionCode).orElse(null);
            if (state != null && state.getWindowStart().equals(windowStart)) {
                freeRemaining = Math.max(0, policy.getFreeQuotaCount() - state.getUsedCount());
            }
        }
        if (freeRemaining > 0 || policy.getTokenCost() <= 0) {
            return new SpendOutcome(SpendOutcome.Settlement.FREE_QUOTA, 0, freeRemaining, balance, null);
        }
        if (balance >= policy.getTokenCost()) {
            return new SpendOutcome(SpendOutcome.Settlement.TOKENS, policy.getTokenCost(), 0, balance, null);
        }
        return new SpendOutcome(SpendOutcome.Settlement.INSUFFICIENT, policy.getTokenCost(), 0, balance, null);
    }

    // ---- internals ---------------------------------------------------------------------------------

    /** Resolves the active policy: role-specific first, else the action default; {@code null} if neither. */
    private ActionCostPolicy resolvePolicy(String actionCode, String roleName) {
        if (roleName != null) {
            var roleSpecific = policies.findByActionCodeAndRoleNameAndActiveTrue(actionCode, roleName);
            if (roleSpecific.isPresent()) {
                return roleSpecific.get();
            }
        }
        return policies.findActiveDefault(actionCode).orElse(null);
    }

    /** Loads the owner's wallet with a write lock, creating it if absent (first spend provisions it). */
    private Wallet lockWallet(WalletOwnerType ownerType, UUID ownerId) {
        return wallets.findForUpdate(ownerType, ownerId)
                .orElseGet(() -> {
                    try {
                        wallets.save(new Wallet(ownerType, ownerId));
                    } catch (DataIntegrityViolationException ignored) {
                        // concurrent create; fall through to re-acquire
                    }
                    return wallets.findForUpdate(ownerType, ownerId)
                            .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR));
                });
    }

    /** Get-or-create the free-quota counter for a wallet/action at the current window. */
    private WalletFreeQuotaState getOrCreateQuotaState(Wallet wallet, String actionCode, Instant windowStart) {
        return quotaStates.findByWalletAndActionCode(wallet, actionCode)
                .orElseGet(() -> {
                    try {
                        return quotaStates.save(new WalletFreeQuotaState(wallet, actionCode, windowStart));
                    } catch (DataIntegrityViolationException raced) {
                        return quotaStates.findByWalletAndActionCode(wallet, actionCode).orElseThrow(() -> raced);
                    }
                });
    }
}

package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletReversalPort;
import com.taarifu.tokens.api.TokenLedgerApi;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production {@link WalletReversalPort} that reverses a refunded top-up from the buyer's token wallet through
 * the published {@code tokens.api} port (ADR-0015 addendum: REFUND/VOID; ARCHITECTURE.md §3.2 — cross-module
 * via {@code api} only). The <b>wired default</b> — selected by
 * {@code taarifu.payments.wallet-reversal.adapter=tokens-api} (see {@code application.yml}).
 *
 * <p>Responsibility: the only place payments touches the tokens module for a reversal, and it does so through
 * the published {@link TokenLedgerApi} — never tokens' tables, repositories, or domain services (ADR-0013).
 * It maps payments' {@link WalletOwnerKind} to the tokens {@link WalletOwnerType} (the enums share constant
 * names by design) and posts a fence-safe {@code REFUND}-type reversal (a debit) of the prior {@code PURCHASE}
 * credit, idempotent on the reversal key.</p>
 *
 * <p><b>🔒 Fence (D18):</b> this adapter can only reverse the convenience-token credit a top-up produced. It
 * never grants or revokes a role, a vote, a signature, a rating, a poll outcome, priority, or verification
 * status, and it never reads a balance for any authorization. A refund undoes convenience/reach only — never
 * democratic weight.</p>
 *
 * <p><b>WHY a direct typed call (no more reflection):</b> {@link TokenLedgerApi} now exposes the fence-safe
 * {@link TokenLedgerApi#refund(WalletOwnerType, UUID, long, String, String) refund} {@code REFUND}-debit
 * method (the ADR-0015 addendum CENTRAL NEED, now landed). The earlier reflective bridge — a temporary
 * isolation crutch while the method was absent — has been removed in favour of this direct, compile-checked
 * call, exactly the migration {@code TokensApiWalletCreditAdapter} already completed for {@code topUp}. The
 * dependency on {@code tokens.api} (and on the {@link WalletOwnerType} enum the published port exposes) is the
 * sanctioned cross-module contract surface, not a reach into tokens' internals.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.wallet-reversal.adapter", havingValue = "tokens-api",
        matchIfMissing = true)
public class TokensApiWalletReversalAdapter implements WalletReversalPort {

    private static final Logger log = LoggerFactory.getLogger(TokensApiWalletReversalAdapter.class);

    /** The published tokens credit/reversal port (the only cross-module surface payments uses). */
    private final TokenLedgerApi tokenLedgerApi;

    /**
     * @param tokenLedgerApi the published {@code tokens.api} port (injected by Spring).
     */
    public TokensApiWalletReversalAdapter(TokenLedgerApi tokenLedgerApi) {
        this.tokenLedgerApi = tokenLedgerApi;
    }

    /**
     * Posts a fence-safe {@code REFUND} reversal via {@code tokens.api}, idempotent on {@code idempotencyKey}.
     *
     * @param ownerType      wallet class (mapped 1:1 by name to the tokens {@link WalletOwnerType}).
     * @param ownerId        wallet owner public id.
     * @param tokenAmount    positive number of tokens to reverse.
     * @param idempotencyKey reversal idempotency key — the top-up's {@code reversal_event_id}; a replay
     *                       reverses nothing (exactly-once, PRD §23.5).
     * @param reason         the redacted machine reason for the reversal (recorded in the tokens ledger; no PII).
     * @return {@code true} if the reversal was posted or idempotently confirmed already-posted.
     */
    @Override
    public boolean reverseTopUp(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount,
                                String idempotencyKey, String reason) {
        // Direct, typed, compile-checked call across the sanctioned tokens.api boundary. The reversal key is
        // the tokens-side idempotency key (reversal_event_id) → the ledger reverses exactly once on retry.
        boolean reversed = tokenLedgerApi.refund(toTokensOwnerType(ownerType), ownerId, tokenAmount,
                idempotencyKey, reason);
        log.info("Top-up reversal posted via tokens.api: ownerType={}, tokenAmount={}, idemKeyPresent={}",
                ownerType, tokenAmount, idempotencyKey != null);
        return reversed;
    }

    /**
     * Maps payments' {@link WalletOwnerKind} to the tokens {@link WalletOwnerType} by name (the enums share
     * constant names; a value that ever diverges fails fast here rather than silently mis-reversing).
     *
     * @param ownerKind payments' owner kind.
     * @return the matching tokens owner type.
     */
    private static WalletOwnerType toTokensOwnerType(WalletOwnerKind ownerKind) {
        return WalletOwnerType.valueOf(ownerKind.name());
    }
}

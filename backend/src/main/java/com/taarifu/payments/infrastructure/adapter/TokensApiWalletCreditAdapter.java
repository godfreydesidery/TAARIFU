package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletCreditPort;
import com.taarifu.tokens.api.TokenLedgerApi;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production {@link WalletCreditPort} that credits a settled top-up to the buyer's token wallet through the
 * published {@code tokens.api} port (ADR-0015; ARCHITECTURE.md §3.2 — cross-module via {@code api} only).
 * Selected by {@code taarifu.payments.wallet-credit.adapter=tokens-api} (the wired default — see
 * {@code application.yml}).
 *
 * <p>Responsibility: the only place payments touches the tokens module, and it does so through the
 * published {@link TokenLedgerApi} interface — never tokens' tables, repositories, or domain services
 * (ADR-0013). It maps payments' {@link WalletOwnerKind} to the tokens {@link WalletOwnerType} (the two
 * enums share constant names by design — see {@link WalletOwnerKind}) and posts a fence-safe {@code PURCHASE}
 * top-up via {@link TokenLedgerApi#topUp}, idempotent on the credit key.</p>
 *
 * <p><b>🔒 Fence (D18):</b> this adapter can only top up the convenience wallet. It never grants a role, a
 * vote, a signature, a rating, a poll outcome, priority, or verification status, and it never reads a
 * balance for any authorization. A purchased token buys convenience/reach only — never democratic weight.</p>
 *
 * <p><b>WHY a direct typed call (no more reflection):</b> {@link TokenLedgerApi} now exposes the fence-safe
 * {@link TokenLedgerApi#topUp(WalletOwnerType, UUID, long, String) topUp} {@code PURCHASE}-credit method
 * (the ADR-0015 CENTRAL NEED, now landed). The earlier reflective bridge — a temporary isolation crutch
 * while the method was absent — has been removed in favour of this direct, compile-checked call. The
 * dependency on {@code tokens.api} (and on the {@link WalletOwnerType} enum the published port exposes) is
 * the sanctioned cross-module contract surface, not a reach into tokens' internals.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.wallet-credit.adapter", havingValue = "tokens-api")
public class TokensApiWalletCreditAdapter implements WalletCreditPort {

    private static final Logger log = LoggerFactory.getLogger(TokensApiWalletCreditAdapter.class);

    /** The published tokens credit port (the only cross-module surface payments uses). */
    private final TokenLedgerApi tokenLedgerApi;

    /**
     * @param tokenLedgerApi the published {@code tokens.api} credit port (injected by Spring).
     */
    public TokensApiWalletCreditAdapter(TokenLedgerApi tokenLedgerApi) {
        this.tokenLedgerApi = tokenLedgerApi;
    }

    /**
     * Posts a fence-safe {@code PURCHASE} top-up via {@code tokens.api}, idempotent on {@code idempotencyKey}.
     *
     * @param ownerType      wallet class (mapped 1:1 by name to the tokens {@link WalletOwnerType}).
     * @param ownerId        wallet owner public id.
     * @param tokenAmount    positive number of tokens to credit.
     * @param idempotencyKey credit idempotency key — the top-up's {@code credit_event_id}; a replay credits
     *                       nothing (exactly-once on webhook redelivery, PRD §23.5).
     * @return {@code true} if the credit was posted or idempotently confirmed already-posted.
     */
    @Override
    public boolean creditPurchase(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount,
                                  String idempotencyKey) {
        // Direct, typed, compile-checked call across the sanctioned tokens.api boundary. The credit key
        // doubles as the tokens-side idempotency key (paymentReference) → the ledger credits exactly once.
        boolean credited = tokenLedgerApi.topUp(toTokensOwnerType(ownerType), ownerId, tokenAmount,
                idempotencyKey);
        log.info("Top-up credited via tokens.api: ownerType={}, tokenAmount={}, idemKeyPresent={}",
                ownerType, tokenAmount, idempotencyKey != null);
        return credited;
    }

    /**
     * Maps payments' {@link WalletOwnerKind} to the tokens {@link WalletOwnerType} by name. The two enums
     * deliberately share constant names ({@code USER}/{@code ORGANIZATION}), so the mapping is a 1:1
     * by-name lookup; a value that ever diverges fails fast here rather than silently mis-crediting.
     *
     * @param ownerKind payments' owner kind.
     * @return the matching tokens owner type.
     */
    private static WalletOwnerType toTokensOwnerType(WalletOwnerKind ownerKind) {
        return WalletOwnerType.valueOf(ownerKind.name());
    }
}

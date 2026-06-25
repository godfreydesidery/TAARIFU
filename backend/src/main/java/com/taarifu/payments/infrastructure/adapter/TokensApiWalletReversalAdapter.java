package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletReversalPort;
import com.taarifu.tokens.api.TokenLedgerApi;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Production {@link WalletReversalPort} that reverses a refunded top-up from the buyer's token wallet through
 * the published {@code tokens.api} port (ADR-0015 addendum: REFUND/VOID; ARCHITECTURE.md §3.2 — cross-module
 * via {@code api} only). Selected by {@code taarifu.payments.wallet-reversal.adapter=tokens-api} (opt-in
 * while the CENTRAL NEED below is pending; the logging stub is the default).
 *
 * <p>Responsibility: the only place payments touches the tokens module for a reversal, and it does so through
 * the published {@link TokenLedgerApi} — never tokens' tables, repositories, or domain services (ADR-0013).
 * It maps payments' {@link WalletOwnerKind} to the tokens {@link WalletOwnerType} (the enums share constant
 * names by design) and posts a fence-safe {@code REFUND}-type reversal of the prior {@code PURCHASE} credit,
 * idempotent on the reversal key.</p>
 *
 * <p><b>🔒 Fence (D18):</b> this adapter can only reverse the convenience-token credit a top-up produced. It
 * never grants or revokes a role, a vote, a signature, a rating, a poll outcome, priority, or verification
 * status, and it never reads a balance for any authorization. A refund undoes convenience/reach only — never
 * democratic weight.</p>
 *
 * <p><b>CENTRAL NEED (ADR-0015 addendum) — and WHY a reflective bridge for now:</b> the published
 * {@link TokenLedgerApi} exposes {@code meter}/{@code reward}/{@code topUp} but <b>no refund/reversal
 * method yet</b>. The tokens owner must add a fence-safe
 * {@code boolean refund(WalletOwnerType, UUID accountPublicId, long amount, String paymentReference)} (a
 * {@code REFUND}-type reversal of a prior {@code PURCHASE}, idempotent on {@code paymentReference}). Until it
 * lands, this adapter binds to it <b>reflectively</b> — exactly the sanctioned isolation crutch ADR-0015 used
 * while {@code topUp} was pending: it compiles today against the existing contract, is opt-in (the logging
 * stub is the wired default), and <b>fails fast with a clear message</b> if selected before the method
 * exists, rather than silently no-op-ing a refund. When the typed method lands, this collapses to a direct,
 * compile-checked call (the reflection is removed) — the same migration {@code TokensApiWalletCreditAdapter}
 * already completed for {@code topUp}.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.wallet-reversal.adapter", havingValue = "tokens-api")
public class TokensApiWalletReversalAdapter implements WalletReversalPort {

    private static final Logger log = LoggerFactory.getLogger(TokensApiWalletReversalAdapter.class);

    /** The expected published refund method name on {@link TokenLedgerApi} (the CENTRAL NEED). */
    private static final String REFUND_METHOD = "refund";

    /** The published tokens credit/reversal port (the only cross-module surface payments uses). */
    private final TokenLedgerApi tokenLedgerApi;

    /**
     * The resolved {@code TokenLedgerApi.refund(...)} method, or {@code null} until the CENTRAL NEED lands.
     * Resolved once at construction so selection of this adapter against an up-to-date contract is a direct
     * dispatch; against the not-yet-extended contract it fails fast on first use with a clear message.
     */
    private final Method refundMethod;

    /**
     * @param tokenLedgerApi the published {@code tokens.api} port (injected by Spring).
     */
    public TokensApiWalletReversalAdapter(TokenLedgerApi tokenLedgerApi) {
        this.tokenLedgerApi = tokenLedgerApi;
        this.refundMethod = resolveRefundMethod(tokenLedgerApi);
    }

    /**
     * Posts a fence-safe {@code REFUND} reversal via {@code tokens.api}, idempotent on {@code idempotencyKey}.
     *
     * @param ownerType      wallet class (mapped 1:1 by name to the tokens {@link WalletOwnerType}).
     * @param ownerId        wallet owner public id.
     * @param tokenAmount    positive number of tokens to reverse.
     * @param idempotencyKey reversal idempotency key — the top-up's {@code reversal_event_id}; a replay
     *                       reverses nothing (exactly-once, PRD §23.5).
     * @return {@code true} if the reversal was posted or idempotently confirmed already-posted.
     * @throws IllegalStateException if the CENTRAL NEED ({@code TokenLedgerApi.refund}) is not yet available —
     *                               a fail-fast that never silently skips a refund.
     */
    @Override
    public boolean reverseTopUp(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount,
                                String idempotencyKey) {
        if (refundMethod == null) {
            throw new IllegalStateException(
                    "tokens.api.TokenLedgerApi.refund(...) is not available yet (ADR-0015 addendum CENTRAL "
                    + "NEED). Keep taarifu.payments.wallet-reversal.adapter=logging until the tokens module "
                    + "publishes the fence-safe REFUND method.");
        }
        try {
            Object result = refundMethod.invoke(tokenLedgerApi, toTokensOwnerType(ownerType), ownerId,
                    tokenAmount, idempotencyKey);
            boolean reversed = Boolean.TRUE.equals(result);
            log.info("Top-up reversal posted via tokens.api: ownerType={}, tokenAmount={}, idemKeyPresent={}",
                    ownerType, tokenAmount, idempotencyKey != null);
            return reversed;
        } catch (ReflectiveOperationException e) {
            // Unwrap to a runtime failure so the refund transaction rolls back atomically (never a half-refund).
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("tokens.api refund invocation failed", cause);
        }
    }

    /**
     * Resolves the published {@code refund(WalletOwnerType, UUID, long, String)} method on the injected
     * {@link TokenLedgerApi}, or {@code null} if the contract does not yet expose it. Looked up on the
     * <i>interface</i> type so it binds to the published contract, not an impl detail.
     *
     * @param api the injected published port.
     * @return the resolved method, or {@code null} when the CENTRAL NEED is still pending.
     */
    private static Method resolveRefundMethod(TokenLedgerApi api) {
        try {
            Method m = TokenLedgerApi.class.getMethod(REFUND_METHOD, WalletOwnerType.class, UUID.class,
                    long.class, String.class);
            log.info("tokens.api refund method resolved — reversal adapter is live.");
            return m;
        } catch (NoSuchMethodException absent) {
            log.warn("tokens.api.TokenLedgerApi.refund(...) not present yet (ADR-0015 addendum CENTRAL NEED); "
                    + "the tokens-api reversal adapter will fail fast if invoked. Use the logging stub until "
                    + "the method lands.");
            return null;
        }
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

package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletCreditPort;
import com.taarifu.tokens.api.TokenLedgerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Production {@link WalletCreditPort} that credits a settled top-up to the buyer's token wallet through the
 * published {@code tokens.api} port (ADR-0015; ARCHITECTURE.md §3.2 — cross-module via {@code api} only).
 * Selected by {@code taarifu.payments.wallet-credit.adapter=tokens-api}.
 *
 * <p>Responsibility: the only place payments touches the tokens module, and it does so through the
 * published {@link TokenLedgerApi} interface — never tokens' tables, repositories, or domain (ADR-0013;
 * {@code ModuleBoundaryTest} stays GREEN because {@code tokens.api} is the sanctioned cross-module
 * contract). It maps payments' {@link WalletOwnerKind} to the tokens owner-type name and posts a
 * fence-safe {@code PURCHASE} top-up, idempotent on the credit key.</p>
 *
 * <p><b>🔒 Fence (D18):</b> this adapter can only top up the convenience wallet. It never grants a role, a
 * vote, a signature, a rating, a poll outcome, priority, or verification status, and it never reads a
 * balance for any authorization. A purchased token buys convenience/reach only.</p>
 *
 * <p><b>CENTRAL NEED — the bridge to {@code TokenLedgerApi.topUp}.</b> {@link TokenLedgerApi} currently
 * exposes only {@code meter} and {@code reward}; neither is a purchase top-up. The tokens owner must add a
 * fence-safe {@code boolean topUp(WalletOwnerType ownerType, UUID ownerId, long tokenAmount, String
 * idempotencyKey)} (a {@code PURCHASE}-type idempotent credit). To keep this module compilable and isolated
 * <i>before</i> that method lands, this adapter invokes {@code topUp} <b>reflectively</b> and, if it is not
 * yet present, <b>fails closed</b> with {@link ErrorCode#SERVICE_UNAVAILABLE} (a top-up is never silently
 * dropped, and no other tokens method is repurposed to fake a credit). Once {@code topUp} exists, replace
 * the reflective call with a direct typed call and delete this note — the contract is unchanged.</p>
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
     * @param ownerType      wallet class (mapped to the tokens owner-type by name).
     * @param ownerId        wallet owner public id.
     * @param tokenAmount    positive number of tokens to credit.
     * @param idempotencyKey credit idempotency key (a replay credits nothing).
     * @return {@code true} if a credit was posted/idempotently confirmed.
     * @throws ApiException {@link ErrorCode#SERVICE_UNAVAILABLE} if the CENTRAL-NEED {@code topUp} method is
     *                      not yet present on {@link TokenLedgerApi} (fail-closed; never a silent drop).
     */
    @Override
    public boolean creditPurchase(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount,
                                  String idempotencyKey) {
        Method topUp = resolveTopUpMethod();
        if (topUp == null) {
            // CENTRAL NEED not yet landed: fail closed so reconciliation does not mark SUCCEEDED with no
            // credit. The relay/caller surfaces SERVICE_UNAVAILABLE; the top-up stays creditable on retry.
            log.error("tokens.api.TokenLedgerApi.topUp(...) is not available — payments cannot credit a "
                    + "top-up. This is the ADR-0015 CENTRAL NEED; the tokens module must add it.");
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            // Pass the owner-type by name so payments never imports tokens' WalletOwnerType enum.
            Object ownerTypeArg = resolveTokensOwnerType(topUp.getParameterTypes()[0], ownerType);
            Object result = topUp.invoke(tokenLedgerApi, ownerTypeArg, ownerId, tokenAmount, idempotencyKey);
            return !(result instanceof Boolean b) || b;
        } catch (ReflectiveOperationException ex) {
            log.error("Top-up credit via tokens.api failed: reason={}", ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Resolves the {@code topUp} method on {@link TokenLedgerApi} by the agreed signature shape, tolerant of
     * the first parameter being the tokens {@code WalletOwnerType} enum (resolved by name).
     *
     * @return the method, or {@code null} if the CENTRAL-NEED method is not yet present.
     */
    private Method resolveTopUpMethod() {
        for (Method m : tokenLedgerApi.getClass().getMethods()) {
            if (m.getName().equals("topUp") && m.getParameterCount() == 4
                    && m.getParameterTypes()[1] == UUID.class
                    && (m.getParameterTypes()[2] == long.class || m.getParameterTypes()[2] == Long.class)
                    && m.getParameterTypes()[3] == String.class) {
                return m;
            }
        }
        return null;
    }

    /**
     * Maps payments' {@link WalletOwnerKind} to the tokens {@code WalletOwnerType} enum constant by name
     * (the enums share constant names by design — see {@link WalletOwnerKind}).
     *
     * @param tokensOwnerTypeClass the first parameter type of the resolved {@code topUp} (the tokens enum).
     * @param ownerKind            payments' owner kind.
     * @return the matching tokens enum constant, or the raw name as a fallback if it is not an enum.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object resolveTokensOwnerType(Class<?> tokensOwnerTypeClass, WalletOwnerKind ownerKind) {
        if (tokensOwnerTypeClass.isEnum()) {
            return Enum.valueOf((Class<Enum>) tokensOwnerTypeClass, ownerKind.name());
        }
        return ownerKind.name();
    }
}

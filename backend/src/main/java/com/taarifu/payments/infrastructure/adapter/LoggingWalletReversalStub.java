package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletReversalPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Opt-in {@link WalletReversalPort} — logs a redacted reversal record and reverses nothing real (ADR-0015
 * addendum: REFUND/VOID; ARCHITECTURE.md §7). A <b>prod-bootable no-op</b> for a stripped dev/CI context that
 * must boot the refund flow with <b>zero cross-module wiring</b> (the tokens module not present).
 *
 * <p>Responsibility: satisfies the port so a tokens-less {@code dev}/{@code test} context boots. It records
 * the reversal intent (owner kind, token amount, idempotency-key presence) and reports success, but moves no
 * real tokens. The refund aggregate transition, idempotency, and fence are all exercised against this port in
 * tests.</p>
 *
 * <p><b>🔒 Fence (D18):</b> like the real adapter, the only action available is "reverse convenience tokens";
 * there is no path to a role/vote/weight. The stub reverses nothing, which is the integrity-safe default.</p>
 *
 * <p><b>WHY no {@code matchIfMissing}</b>: now that {@code tokens.api.TokenLedgerApi.refund(...)} has landed,
 * the real {@code TokensApiWalletReversalAdapter} is the wired default ({@code matchIfMissing = true} on
 * {@code adapter=tokens-api}). This stub is selected only by an explicit
 * {@code taarifu.payments.wallet-reversal.adapter=logging}; the two are mutually exclusive on the property so
 * exactly one {@link WalletReversalPort} bean exists in every environment (mirrors the wallet-credit pair).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.wallet-reversal.adapter", havingValue = "logging")
public class LoggingWalletReversalStub implements WalletReversalPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingWalletReversalStub.class);

    /**
     * Logs the redacted reversal intent and reports success (no real tokens moved).
     *
     * @param ownerType      wallet class.
     * @param ownerId        wallet owner public id.
     * @param tokenAmount    tokens that would be reversed.
     * @param idempotencyKey reversal idempotency key.
     * @param reason         redacted machine reason (logged only; never PII).
     * @return {@code true} (a stub always "succeeds" so the REFUNDED transition completes in tests).
     */
    @Override
    public boolean reverseTopUp(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount,
                                String idempotencyKey, String reason) {
        log.info("StubWalletReversal: refund reversal recorded (no real tokens moved); ownerType={}, "
                        + "tokenAmount={}, idemKeyPresent={}",
                ownerType, tokenAmount, idempotencyKey != null);
        return true;
    }
}

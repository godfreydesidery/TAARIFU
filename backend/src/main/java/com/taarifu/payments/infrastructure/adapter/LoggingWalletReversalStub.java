package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletReversalPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default {@link WalletReversalPort} — logs a redacted reversal record and reverses nothing real (ADR-0015
 * addendum: REFUND/VOID; ARCHITECTURE.md §7). The <b>prod-bootable no-op</b> selected while the
 * {@code tokens.api} refund method (the CENTRAL NEED) is not yet wired, so the whole refund flow boots and is
 * testable with <b>zero cross-module wiring</b>.
 *
 * <p>Responsibility: satisfies the port so {@code dev}/{@code test} and a no-config production context boot.
 * It records the reversal intent (owner kind, token amount, idempotency-key presence) and reports success,
 * but moves no real tokens. The refund aggregate transition, idempotency, and fence are all exercised against
 * this port in tests.</p>
 *
 * <p><b>🔒 Fence (D18):</b> like the real adapter, the only action available is "reverse convenience tokens";
 * there is no path to a role/vote/weight. The stub reverses nothing, which is the integrity-safe default.</p>
 *
 * <p><b>WHY {@code matchIfMissing = true}</b>: with no {@code taarifu.payments.wallet-reversal.adapter} set,
 * this is the one active bean; the real {@code TokensApiWalletReversalAdapter} is selected only by
 * {@code adapter=tokens-api}, and the two are mutually exclusive on the property so exactly one
 * {@link WalletReversalPort} bean exists in every environment.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.wallet-reversal.adapter", havingValue = "logging",
        matchIfMissing = true)
public class LoggingWalletReversalStub implements WalletReversalPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingWalletReversalStub.class);

    /**
     * Logs the redacted reversal intent and reports success (no real tokens moved).
     *
     * @param ownerType      wallet class.
     * @param ownerId        wallet owner public id.
     * @param tokenAmount    tokens that would be reversed.
     * @param idempotencyKey reversal idempotency key.
     * @return {@code true} (a stub always "succeeds" so the REFUNDED transition completes in tests).
     */
    @Override
    public boolean reverseTopUp(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount,
                                String idempotencyKey) {
        log.info("StubWalletReversal: refund reversal recorded (no real tokens moved); ownerType={}, "
                        + "tokenAmount={}, idemKeyPresent={}",
                ownerType, tokenAmount, idempotencyKey != null);
        return true;
    }
}

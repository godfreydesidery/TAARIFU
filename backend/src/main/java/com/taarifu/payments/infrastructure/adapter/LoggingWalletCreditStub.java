package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletCreditPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default {@link WalletCreditPort} — logs a redacted credit record and credits nothing real (ADR-0015;
 * ARCHITECTURE.md §7). The <b>prod-bootable no-op</b> selected when the tokens-api credit adapter is not
 * wired, so the whole top-up flow boots and is testable with <b>zero cross-module wiring</b>.
 *
 * <p>Responsibility: satisfies the port so {@code dev}/{@code test} and a no-config production context boot.
 * It records the credit intent (owner kind, token amount, idempotency-key presence) and reports success,
 * but moves no real tokens — used while {@code tokens.api.TokenLedgerApi.topUp(...)} (the CENTRAL NEED) is
 * pending. The reconciliation flow, idempotency, and fence are all exercised against this port in tests.</p>
 *
 * <p><b>🔒 Fence (D18):</b> like the real adapter, the only action available is "add convenience tokens";
 * there is no path to a role/vote/weight. The stub credits nothing, which is the integrity-safe default.</p>
 *
 * <p><b>WHY {@code matchIfMissing = true}</b>: with no {@code taarifu.payments.wallet-credit.adapter} set,
 * this is the one active bean; the real {@code TokensApiWalletCreditAdapter} is selected only by
 * {@code adapter=tokens-api}, and the two are mutually exclusive on the property so exactly one
 * {@link WalletCreditPort} bean exists in every environment.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.wallet-credit.adapter", havingValue = "logging",
        matchIfMissing = true)
public class LoggingWalletCreditStub implements WalletCreditPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingWalletCreditStub.class);

    /**
     * Logs the redacted credit intent and reports success (no real tokens moved).
     *
     * @param ownerType      wallet class.
     * @param ownerId        wallet owner public id.
     * @param tokenAmount    tokens that would be credited.
     * @param idempotencyKey credit idempotency key.
     * @return {@code true} (a stub always "succeeds" so the SUCCEEDED transition completes in tests).
     */
    @Override
    public boolean creditPurchase(WalletOwnerKind ownerType, UUID ownerId, long tokenAmount,
                                  String idempotencyKey) {
        log.info("StubWalletCredit: top-up credit recorded (no real tokens moved); ownerType={}, "
                        + "tokenAmount={}, idemKeyPresent={}",
                ownerType, tokenAmount, idempotencyKey != null);
        return true;
    }
}

package com.taarifu.payments.application.service;

import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.payments.api.event.TopUpSucceeded;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.domain.port.WalletCreditPort;
import com.taarifu.payments.domain.repository.TopUpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Application service that reconciles a verified mobile-money settlement to exactly one {@link TopUp} and
 * credits the buyer's token wallet — <b>idempotently, reconciliation-driven, never trust-the-callback</b>
 * (ADR-0015; PRD §23.5).
 *
 * <p>Responsibility: given a (signature-verified) callback's {@code (provider, providerRef)}, find the one
 * matching top-up under a row lock, confirm settlement <b>against the provider</b>
 * ({@link MobileMoneyGateway#verifySettled}), then transition it to SUCCEEDED and credit the wallet exactly
 * once. A duplicate/out-of-order callback on an already-terminal row is a no-op.</p>
 *
 * <p><b>Idempotency (PRD §23.5), three layers:</b></p>
 * <ul>
 *   <li>the partial-unique {@code (provider, provider_ref)} index → one settlement maps to one row;</li>
 *   <li>{@link TopUp#isTerminal()} short-circuits a redelivered callback on a SUCCEEDED/FAILED row;</li>
 *   <li>the wallet credit is keyed on a stable {@code credit_event_id} so the ledger credits exactly once
 *       even if the credit call is retried.</li>
 * </ul>
 *
 * <p><b>🔒 Civic-integrity fence (binding — D18, PRD §23.5):</b> the only effect of a SUCCEEDED settlement
 * is a convenience-wallet top-up via {@link WalletCreditPort#creditPurchase}. This service has <b>no</b>
 * dependency on any binding-action module, never grants a role/vote/weight, and never reads a balance for
 * authorization. A purchased token buys convenience/reach only — never democratic weight.</p>
 *
 * <p><b>Atomicity (ADR-0014):</b> the SUCCEEDED write, the wallet credit, and the {@code TopUpSucceeded}
 * outbox append all happen in <b>one transaction</b> — a crash can never leave the row SUCCEEDED without
 * its credit, nor credit without the durable event.</p>
 */
@Service
@Transactional
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TopUpRepository topUps;
    private final MobileMoneyGateway gateway;
    private final WalletCreditPort walletCredit;
    private final OutboxWriter outbox;

    /**
     * @param topUps       top-up persistence (locked load by provider reference).
     * @param gateway      the rail (out-of-band settlement confirmation — never trust-the-callback).
     * @param walletCredit the fence-safe top-up credit port (tokens-api adapter or logging stub).
     * @param outbox       the transactional outbox writer for the async {@code TopUpSucceeded} event.
     */
    public ReconciliationService(TopUpRepository topUps, MobileMoneyGateway gateway,
                                 WalletCreditPort walletCredit, OutboxWriter outbox) {
        this.topUps = topUps;
        this.gateway = gateway;
        this.walletCredit = walletCredit;
        this.outbox = outbox;
    }

    /**
     * Reconciles one settlement callback to its top-up and, if the provider confirms settlement, credits the
     * wallet exactly once.
     *
     * @param provider    the settling rail (from the webhook path).
     * @param providerRef the settlement reference the callback claims (already signature-verified upstream).
     * @return the up-to-date {@link TopUp}, or {@code null} if no row matches the reference (an unknown or
     *         not-yet-visible reference — the callback is acknowledged but causes no state change).
     */
    public TopUp reconcile(MobileMoneyProvider provider, String providerRef) {
        if (providerRef == null || providerRef.isBlank()) {
            return null;
        }
        TopUp topUp = topUps.findForUpdateByProviderRef(provider, providerRef).orElse(null);
        if (topUp == null) {
            // Unknown/not-yet-visible reference: acknowledge, change nothing (the rail will retry; PRD §23.5).
            log.info("Reconcile: no top-up for provider={} (ref present={}); ignored", provider, true);
            return null;
        }
        if (topUp.isTerminal()) {
            // Duplicate/out-of-order callback on an already-settled/failed row → no-op (idempotent).
            return topUp;
        }

        // NEVER trust-the-callback: confirm settlement against the provider before any credit (PRD §23.5).
        if (!gateway.verifySettled(providerRef)) {
            // Not yet settled (or rejected): leave PENDING so a later confirming callback can complete it.
            return topUp;
        }

        // Stable, per-top-up credit idempotency key → the ledger credits exactly once even on retry.
        UUID creditKey = UUID.nameUUIDFromBytes(("topup-credit:" + topUp.getPublicId()).getBytes());
        walletCredit.creditPurchase(topUp.getWalletOwnerType(), topUp.getBuyerId(),
                topUp.getTokenAmount(), creditKey.toString());

        topUp.markSucceeded(creditKey);
        TopUp saved = topUps.save(topUp);

        // Async receipt/analytics fan-out — ids/amounts only, NO PII (ADR-0014 §1/§4). Same transaction.
        outbox.append(EventEnvelope.of(
                TopUpSucceeded.EVENT_TYPE,
                TopUpSucceeded.AGGREGATE_TYPE,
                saved.getPublicId(),
                new TopUpSucceeded(saved.getPublicId(), saved.getBuyerId(), saved.getWalletOwnerType(),
                        saved.getTokenAmount(), saved.getProvider()),
                Instant.now()));

        log.info("Top-up settled and wallet credited: provider={}, tokenAmount={}", provider,
                saved.getTokenAmount());
        return saved;
    }
}

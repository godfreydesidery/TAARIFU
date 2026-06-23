package com.taarifu.tokens.domain.port;

import com.taarifu.tokens.domain.model.enums.PaymentProviderType;

/**
 * Outbound port for token-purchase settlement via mobile money / card — <b>Phase 2 seam</b>
 * (PRD §23.3/§23.5/§23.6, §21 EI-20, D19; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: abstracts initiating a collection (STK push) and verifying a settlement so the tokens
 * module never embeds a vendor SDK (M-Pesa/Tigo Pesa/Airtel Money/HaloPesa/card). Concrete adapters live in
 * {@code infrastructure.adapter}; a {@link com.taarifu.tokens.infrastructure.adapter.StubPaymentProvider}
 * lets the whole system boot and tests run with <b>zero external calls</b> (ARCHITECTURE.md §7).</p>
 *
 * <p><b>Degradation (PRD §23):</b> the free token path is <i>always</i> available; if no payment adapter is
 * configured/healthy, purchase is simply unavailable and the citizen continues on free quotas — never
 * blocked. Settlement is <b>idempotent and reconciliation-driven</b>, never trust-the-callback: Tanzanian
 * mobile-money webhooks may be duplicated or out of order, so a verified provider reference, not the raw
 * callback, is what posts the one-and-only {@code PURCHASE} credit (PRD §23.5 anti-fraud).</p>
 *
 * <p>WHY this port exists in MVP though purchase is Phase 2: it locks the seam (D19) so the purchase flow,
 * webhook verification, and reconciliation can be added later without touching the wallet/ledger domain.</p>
 */
public interface PaymentProvider {

    /**
     * @return the rail this adapter settles (e.g. {@code MPESA}); {@code null}-safe identity for routing.
     */
    PaymentProviderType providerType();

    /**
     * Initiates a collection (STK push) for an amount, returning a provider reference to reconcile against.
     *
     * <p>WHY it returns a reference rather than a final status: mobile-money collection is asynchronous —
     * the citizen approves on their handset and settlement arrives later via webhook/poll. The reference is
     * the correlation handle reconciliation uses to find the one matching {@code Payment} row.</p>
     *
     * @param request the initiation request (amount in minor units, payer MSISDN, our idempotency key).
     * @return the provider's initiation result (its reference + initial state).
     */
    InitiationResult initiateCollection(CollectionRequest request);

    /**
     * Verifies, against the provider (not the raw callback), whether a referenced collection has settled.
     *
     * @param providerRef the provider settlement reference from {@link #initiateCollection}.
     * @return {@code true} if the provider confirms settlement; never derived from an unverified webhook body.
     */
    boolean verifySettled(String providerRef);

    /**
     * Initiation request. MSISDN is the payer's mobile-money number; it is sensitive and never logged in
     * full (PRD §18 PII redaction). Amount is in the currency's minor units (never floating-point money).
     *
     * @param amountMinor    amount in minor currency units.
     * @param currency       ISO-4217 code (e.g. {@code TZS}).
     * @param payerMsisdn    payer mobile-money number (E.164); redacted in logs.
     * @param idempotencyKey our dedup key for this initiation.
     */
    record CollectionRequest(long amountMinor, String currency, String payerMsisdn, String idempotencyKey) {
    }

    /**
     * Provider initiation result.
     *
     * @param providerRef the provider's correlation reference (used by reconciliation).
     * @param accepted    whether the provider accepted the initiation (push sent).
     */
    record InitiationResult(String providerRef, boolean accepted) {
    }
}

package com.taarifu.payments.domain.port;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;

/**
 * Outbound port for mobile-money collection (STK push), callback verification, and settlement
 * reconciliation (ADR-0015; PRD §23.5/§23.6, §21 EI-20, DI1; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: abstracts the Tanzanian mobile-money rails (M-Pesa / Tigo Pesa / Airtel Money /
 * HaloPesa) so the payments domain never embeds a vendor SDK. Concrete adapters live in
 * {@code infrastructure.adapter}; a {@link com.taarifu.payments.infrastructure.adapter.LoggingMobileMoneyGatewayStub}
 * is the prod-bootable default that lets the whole system boot and tests run with <b>zero external
 * calls</b>.</p>
 *
 * <p><b>🔒 Never-trust-the-callback (PRD §23.5):</b> TZ mobile-money webhooks may be duplicated or out of
 * order. The credit is posted only after {@link #verifySettled(String)} confirms settlement against the
 * provider out-of-band — never derived from the raw callback body. {@link #verifyCallbackSignature} is the
 * first, <b>fail-closed</b> gate: an unverified body causes no state change.</p>
 *
 * <p><b>Degradation (EI-20):</b> the free token path is always available; if no rail is configured/healthy,
 * purchase is simply unavailable and the citizen continues on free quotas — never blocked. Adapters
 * degrade-don't-crash: a transport failure returns a not-accepted {@link InitiationResult}, never an
 * exception that 500s the citizen.</p>
 *
 * <p><b>No vendor imports here</b> (ARCHITECTURE.md §3.4): this is a plain interface; vendor types live only
 * in the adapter. Secrets (per-provider HMAC keys, endpoints) are read by adapters from the environment —
 * never from source (PRD §18, CLAUDE.md §12).</p>
 */
public interface MobileMoneyGateway {

    /**
     * @return the rail this adapter settles (e.g. {@code MPESA}); used to route a webhook/initiation.
     */
    MobileMoneyProvider provider();

    /**
     * Initiates a collection (STK push) for an amount, returning a provider reference to reconcile against.
     *
     * <p>WHY it returns a reference rather than a final status: collection is asynchronous — the citizen
     * approves on their handset and settlement arrives later via webhook/poll. The reference is the
     * correlation handle reconciliation uses to find the one matching {@link
     * com.taarifu.payments.domain.model.TopUp}.</p>
     *
     * @param request initiation request (amount in minor units, payer MSISDN, our idempotency key).
     * @return the provider's initiation result (its reference + whether the push was accepted). Never
     *         {@code null}; on transport failure returns {@code new InitiationResult(null, false)}.
     */
    InitiationResult initiateCollection(CollectionRequest request);

    /**
     * Verifies the HMAC signature of a raw callback body against the per-provider shared secret —
     * <b>fail-closed</b>.
     *
     * <p>WHY over the raw bytes: an HMAC must be computed over the exact bytes received; re-serialising a
     * parsed body would change whitespace/ordering and break verification (and open a forgery gap). A
     * verification failure means the callback is ignored with no state change and no information leak about
     * why (no oracle).</p>
     *
     * @param rawBody         the exact bytes of the callback request body.
     * @param signatureHeader the signature presented by the caller (provider-specific header value).
     * @return {@code true} iff the signature is valid for this provider's secret; {@code false} otherwise.
     */
    boolean verifyCallbackSignature(byte[] rawBody, String signatureHeader);

    /**
     * Parses a (already signature-verified) callback body into the fields reconciliation needs.
     *
     * @param rawBody the callback body bytes.
     * @return the parsed result; {@code settled} is the callback's <i>claim</i> only — it is confirmed by
     *         {@link #verifySettled(String)} before any credit (never trust-the-callback).
     */
    CallbackResult parseCallback(byte[] rawBody);

    /**
     * Confirms, <b>against the provider</b> (not the raw callback), whether a referenced collection settled.
     *
     * @param providerRef the provider settlement reference.
     * @return {@code true} if the provider confirms settlement; this is the only signal that authorises a
     *         {@code PURCHASE} credit (PRD §23.5).
     */
    boolean verifySettled(String providerRef);

    /**
     * Initiation request. The MSISDN is the payer's mobile-money number; it is sensitive PII and is
     * <b>never logged in full</b> (PRD §18). Amount is in the currency's minor units (never floating-point
     * money).
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
     * @param providerRef the provider's correlation reference (used by reconciliation), or {@code null} if
     *                    the initiation was not accepted.
     * @param accepted    whether the provider accepted the initiation (push sent).
     */
    record InitiationResult(String providerRef, boolean accepted) {
    }

    /**
     * Parsed callback fields needed to reconcile.
     *
     * @param providerRef the settlement reference this callback refers to.
     * @param settled     the callback's <i>claimed</i> settlement outcome (confirmed via
     *                    {@link #verifySettled(String)} before crediting).
     */
    record CallbackResult(String providerRef, boolean settled) {
    }
}

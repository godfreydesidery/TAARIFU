package com.taarifu.payments.domain.model.enums;

/**
 * The Tanzanian mobile-money rail that settles a {@link com.taarifu.payments.domain.model.TopUp}
 * (ADR-0015; PRD §23.4/§23.6, §21 EI-20).
 *
 * <p>Responsibility: identifies which {@link com.taarifu.payments.domain.port.MobileMoneyGateway} adapter
 * processes a top-up. These rails are STK-push / collection-callback based and asynchronous; their callbacks
 * may be duplicated or out of order, so reconciliation is idempotent and ledger-driven, never
 * trust-the-callback (PRD §23.5 anti-fraud).</p>
 *
 * <p>WHY a payments-owned enum (not reusing {@code tokens.PaymentProviderType}): module isolation
 * (ADR-0013) — payments must not import a tokens internal type. CARD is intentionally absent: this Phase-2
 * increment is <b>mobile money only</b> (a card rail is a separate revisit trigger, ADR-0015).</p>
 */
public enum MobileMoneyProvider {

    /** Vodacom M-Pesa (Tanzania). */
    MPESA,

    /** Tigo Pesa (Mixx by Yas). */
    TIGOPESA,

    /** Airtel Money. */
    AIRTELMONEY,

    /** HaloPesa (Halotel). */
    HALOPESA
}

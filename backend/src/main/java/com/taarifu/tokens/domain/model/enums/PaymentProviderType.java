package com.taarifu.tokens.domain.model.enums;

/**
 * The mobile-money / card provider that settles a Phase 2 token {@link
 * com.taarifu.tokens.domain.model.Payment} (PRD §23.3/§23.4, §21 EI-20, D19).
 *
 * <p>Responsibility: identifies which {@link com.taarifu.tokens.domain.port.PaymentProvider} adapter
 * processed a purchase. Tanzanian mobile money is the primary rail; these are STK-push/collection-callback
 * based, asynchronous, and may deliver duplicate or out-of-order webhooks — so reconciliation is idempotent
 * and ledger-driven, never trust-the-callback (PRD §23.5 anti-fraud).</p>
 *
 * <p>WHY this exists in MVP though purchase is Phase 2: the schema seam (D19) is modelled now so the ledger
 * already distinguishes a {@code PURCHASE} credit's settlement rail without a later breaking migration.</p>
 */
public enum PaymentProviderType {

    /** Vodacom M-Pesa (Tanzania). */
    MPESA,

    /** Tigo Pesa (Mixx by Yas). */
    TIGOPESA,

    /** Airtel Money. */
    AIRTELMONEY,

    /** HaloPesa (Halotel). */
    HALOPESA,

    /** Card (Visa/Mastercard) via a card acquirer. */
    CARD
}

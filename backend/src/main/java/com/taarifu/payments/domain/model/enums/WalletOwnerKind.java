package com.taarifu.payments.domain.model.enums;

/**
 * Which kind of account owns the wallet a {@link com.taarifu.payments.domain.model.TopUp} credits
 * (ADR-0015).
 *
 * <p>Responsibility: lets payments name the wallet owner class without importing the tokens module's
 * internal {@code WalletOwnerType} (module isolation, ADR-0013). The names deliberately mirror the tokens
 * enum's constants so the {@code WalletCreditPort} can pass the name string across the published
 * {@code tokens.api} boundary and the tokens adapter can map it back 1:1.</p>
 */
public enum WalletOwnerKind {

    /** A citizen / individual user account wallet. */
    USER,

    /** An organisation account wallet (B2B workspace, later increment). */
    ORGANIZATION
}

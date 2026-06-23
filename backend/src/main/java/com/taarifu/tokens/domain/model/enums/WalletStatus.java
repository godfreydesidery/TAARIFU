package com.taarifu.tokens.domain.model.enums;

/**
 * Lifecycle status of a {@link com.taarifu.tokens.domain.model.Wallet} (PRD §23.4).
 *
 * <p>Responsibility: gates whether a wallet may spend/earn. A frozen wallet (abuse review, payment
 * dispute) still has a derivable balance and ledger history but cannot transact.</p>
 *
 * <p>WHY {@code FROZEN} is distinct from soft-delete: freezing is a reversible operational state set by
 * admin/anti-abuse, not a tombstone; the wallet and its append-only ledger remain intact (PRD §23.5
 * anti-abuse). The civic free-quota path is unaffected by freezing only where policy says so — freezing
 * blocks token spend, never the citizen's right to be heard via the free path (PRD §23.2, §23.5).</p>
 */
public enum WalletStatus {

    /** Normal operating state — spend/earn/grant permitted. */
    ACTIVE,

    /** Temporarily blocked from token spend (anti-abuse/dispute); reversible by admin. */
    FROZEN,

    /** Permanently closed (e.g. account erasure tombstone); retained for ledger integrity. */
    CLOSED
}

package com.taarifu.tokens.application.service;

import java.util.UUID;

/**
 * The result of metering one attempt at a metered action through {@link MeteringService} (PRD §23.2).
 *
 * <p>Responsibility: tells the caller <b>how</b> the action was paid for — from the recurring free quota,
 * from token balance, or not at all (insufficient) — plus the resulting free-uses-remaining and token
 * balance, so the UX can transparently show "you have N free left / this cost X tokens" before and after
 * (PRD §23.5 transparency). When the free quota is exhausted, the UX is required to offer "wait for refresh"
 * (always free) before "spend tokens" — the free path is never hidden (PRD §23.2).</p>
 *
 * @param settledBy            how the action was paid for.
 * @param tokensCharged        tokens debited (0 when settled from free quota).
 * @param freeRemaining        free uses left in the current window after this attempt.
 * @param balanceAfter         the wallet's token balance after this attempt.
 * @param ledgerTransactionId  public id of the SPEND ledger entry, or {@code null} if none was written
 *                             (free-quota settlement or insufficient).
 */
public record SpendOutcome(
        Settlement settledBy,
        long tokensCharged,
        int freeRemaining,
        long balanceAfter,
        UUID ledgerTransactionId
) {

    /** How a metered action was settled. */
    public enum Settlement {
        /** Covered by the recurring free quota — no tokens moved (PRD §23.2 free path). */
        FREE_QUOTA,
        /** Free quota exhausted; paid from token balance (a SPEND ledger entry was appended). */
        TOKENS,
        /** Free quota exhausted and balance insufficient; the action was not metered (caller must reject). */
        INSUFFICIENT
    }

    /** @return {@code true} if the action may proceed (settled by free quota or tokens). */
    public boolean isAllowed() {
        return settledBy != Settlement.INSUFFICIENT;
    }
}

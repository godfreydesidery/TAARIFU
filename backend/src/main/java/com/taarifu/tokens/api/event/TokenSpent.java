package com.taarifu.tokens.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published domain event: a metered action was paid for with tokens (ARCHITECTURE.md §3.1/§8 — events are
 * the only async cross-module contract; PRD §23).
 *
 * <p>Responsibility: lets analytics/notifications react to token spend without coupling to tokens' internals.
 * It is emitted only for an actual {@code SPEND} (free-quota settlements are not "spend" — no value moved).</p>
 *
 * <p><b>Fence note (D18):</b> a {@code TokenSpent} event can never represent a binding democratic action —
 * those are never metered, so they never produce a spend or this event. Consumers must therefore never infer
 * any democratic-weight effect from this event (PRD §23 fence).</p>
 *
 * <p>WHY it carries ids/codes only (no PII): cross-module events stay minimal and privacy-safe (PRD §18).</p>
 *
 * @param walletPublicId      the wallet that was debited.
 * @param ownerId             the owner's public id.
 * @param actionCode          the metered action.
 * @param tokensCharged       tokens debited.
 * @param balanceAfter        the wallet balance after the spend.
 * @param refEntityType       type of the targeted entity, or {@code null}.
 * @param refEntityId         public id of the targeted entity, or {@code null}.
 * @param ledgerTransactionId the SPEND ledger entry's public id.
 * @param occurredAt          the spend instant (UTC).
 */
public record TokenSpent(
        UUID walletPublicId,
        UUID ownerId,
        String actionCode,
        long tokensCharged,
        long balanceAfter,
        String refEntityType,
        UUID refEntityId,
        UUID ledgerTransactionId,
        Instant occurredAt
) {
}

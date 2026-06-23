package com.taarifu.tokens.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for one ledger entry (PRD §23.5 transparency — "users see their … ledger history").
 *
 * <p>Responsibility: the boundary shape for the {@code GET /me/wallet/ledger} history. It exposes the
 * movement type, magnitude, the running balance it produced, the action/reason, and an optional reference
 * to the causing entity — enough for a transparent, auditable history without leaking internal ids or PII.</p>
 *
 * @param id            the entry's public id.
 * @param type          movement type (GRANT/EARN/SPEND/PURCHASE/REFUND/EXPIRE/ADJUST).
 * @param amount        positive magnitude in whole tokens (the type carries the sign).
 * @param balanceAfter  the wallet balance immediately after this entry.
 * @param actionCode    metered action / reason code, or {@code null}.
 * @param reason        human/machine reason, or {@code null}.
 * @param refEntityType type of the causing entity, or {@code null}.
 * @param refEntityId   public id of the causing entity, or {@code null}.
 * @param createdAt     append instant (UTC).
 */
public record TokenTransactionDto(
        UUID id,
        String type,
        long amount,
        long balanceAfter,
        String actionCode,
        String reason,
        String refEntityType,
        UUID refEntityId,
        Instant createdAt
) {
}

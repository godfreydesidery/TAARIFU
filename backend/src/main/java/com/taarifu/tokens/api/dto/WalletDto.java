package com.taarifu.tokens.api.dto;

import java.util.UUID;

/**
 * Response DTO for a wallet (PRD §23.1, §23.5 transparency — "users see their wallet").
 *
 * <p>Responsibility: the boundary shape for {@code GET /me/wallet}. Exposes only the owner-safe view:
 * the public id, balance, and status. The internal numeric id is never exposed (ADR-0006), and the owner
 * reference is the caller's own id (the endpoint is own-wallet-only — see the controller's authorization).</p>
 *
 * @param id      the wallet's public id.
 * @param ownerId the owner's public id (the authenticated caller).
 * @param balance the current token balance (derived from the ledger; never balance-gates democratic acts).
 * @param status  the wallet's operating status (ACTIVE/FROZEN/CLOSED).
 */
public record WalletDto(
        UUID id,
        UUID ownerId,
        long balance,
        String status
) {
}

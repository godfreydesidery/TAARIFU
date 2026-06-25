package com.taarifu.payments.api.dto;

import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound view of a {@link TopUp} for the initiate/status endpoints (ADR-0015; ARCHITECTURE.md §5).
 *
 * <p>Responsibility: the boundary DTO — entities never leak past the module (CLAUDE.md §8). It exposes the
 * public id, status, money/token amounts, and rail so a citizen can poll their purchase. It deliberately
 * <b>omits</b> the payer MSISDN and the internal numeric id (PII / enumeration safety, PRD §17/§18) and the
 * raw provider reference (an internal reconciliation handle).</p>
 *
 * @param id          the top-up's public id (the citizen-facing reference).
 * @param status      the lifecycle status.
 * @param provider    the mobile-money rail.
 * @param amountMinor amount in minor currency units.
 * @param tokenAmount tokens credited on success.
 * @param currency    ISO-4217 currency code.
 * @param createdAt   when the top-up was initiated (UTC).
 */
public record TopUpDto(
        UUID id,
        TopUpStatus status,
        MobileMoneyProvider provider,
        long amountMinor,
        long tokenAmount,
        String currency,
        Instant createdAt
) {

    /**
     * Maps a {@link TopUp} entity to its boundary view.
     *
     * @param t the entity (never {@code null}).
     * @return the DTO.
     */
    public static TopUpDto from(TopUp t) {
        return new TopUpDto(t.getPublicId(), t.getStatus(), t.getProvider(),
                t.getAmountMinor(), t.getTokenAmount(), t.getCurrency(), t.getCreatedAt());
    }
}

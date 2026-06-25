package com.taarifu.payments.api.dto;

import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * ADMIN-facing view of a {@link TopUp} for the payments operations console (ADR-0015 addendum;
 * ARCHITECTURE.md §5, PRD §18).
 *
 * <p>Responsibility: the boundary DTO for an administrator listing/searching payments — entities never leak
 * past the module (CLAUDE.md §8). It exposes more than the citizen {@link TopUpDto} (the buyer id, the
 * provider reference, the lifecycle/failure detail) because an operator needs it for support and
 * reconciliation, but it still carries <b>no secret and no PII</b>:</p>
 * <ul>
 *   <li>there is <b>no MSISDN</b> here — the payer number is never persisted on the {@link TopUp}, so there
 *       is nothing to mask (the privacy property holds by construction, ADR-0015 §1; the brief's "MSISDN
 *       masked" requirement is satisfied because the field simply does not exist on the aggregate);</li>
 *   <li>{@code buyerId} is an opaque account public id (never a national/voter ID — no PII, ADR-0013);</li>
 *   <li>{@code failureReason}/{@code reversalReason} are redacted machine codes only (PRD §18).</li>
 * </ul>
 *
 * @param id            the top-up public id (the operator-facing reference).
 * @param buyerId       the buyer's account public id (opaque UUID; no PII).
 * @param status        the lifecycle status.
 * @param provider      the mobile-money rail.
 * @param providerRef   the provider settlement reference (an internal reconciliation handle; not a secret).
 * @param amountMinor   amount in minor currency units.
 * @param tokenAmount   tokens credited on success.
 * @param currency      ISO-4217 currency code.
 * @param failureReason redacted machine reason if FAILED, else {@code null}.
 * @param reversalReason redacted machine reason if VOIDED/REFUNDED, else {@code null}.
 * @param createdAt     when the top-up was initiated (UTC).
 * @param updatedAt     when the top-up last changed state (UTC), or {@code null}.
 */
public record AdminPaymentDto(
        UUID id,
        UUID buyerId,
        TopUpStatus status,
        MobileMoneyProvider provider,
        String providerRef,
        long amountMinor,
        long tokenAmount,
        String currency,
        String failureReason,
        String reversalReason,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Maps a {@link TopUp} entity to its admin boundary view.
     *
     * @param t the entity (never {@code null}).
     * @return the DTO.
     */
    public static AdminPaymentDto from(TopUp t) {
        return new AdminPaymentDto(
                t.getPublicId(), t.getBuyerId(), t.getStatus(), t.getProvider(), t.getProviderRef(),
                t.getAmountMinor(), t.getTokenAmount(), t.getCurrency(), t.getFailureReason(),
                t.getReversalReason(), t.getCreatedAt(), t.getUpdatedAt());
    }
}

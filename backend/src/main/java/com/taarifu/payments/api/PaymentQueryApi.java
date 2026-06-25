package com.taarifu.payments.api;

import com.taarifu.payments.api.dto.AdminPaymentDto;
import com.taarifu.payments.api.dto.PaymentTotalsDto;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * The payments module's <b>published, in-process API</b> for ADMIN payment queries (ARCHITECTURE.md §3.2 —
 * other modules talk only through a module's published {@code api} package; ADR-0013). It is the read-side
 * contract an admin/operations surface depends on to list/search top-ups, total a window, and read one
 * top-up's status — without importing payments' internals or touching {@code top_up} directly.
 *
 * <p>Responsibility: a small, read-only contract returning boundary DTOs ({@link AdminPaymentDto},
 * {@link PaymentTotalsDto}) only — never the {@code TopUp} entity (the
 * {@code ModuleBoundaryTest.noEntityInPublishedApiOrEvents} rule keeps this package entity-free). The
 * vocabulary it exposes ({@link TopUpStatus}, {@link MobileMoneyProvider}) is payments-owned value-type
 * enums, the sanctioned cross-module contract surface.</p>
 *
 * <p><b>Privacy (PRD §18):</b> nothing returned here carries a secret or PII — no MSISDN (never stored), no
 * national/voter ID; {@code buyerId} is an opaque account UUID. Authorization (ROLE_ADMIN) is enforced at the
 * controller; this port assumes an already-authorised caller.</p>
 *
 * <p><b>🔒 Fence (D18):</b> this is a read-only money-movement view. It exposes no path to a binding
 * democratic action and never surfaces a token balance.</p>
 */
public interface PaymentQueryApi {

    /**
     * Lists/searches top-ups by optional status / provider / created-at window, paged.
     *
     * @param status   optional status filter ({@code null} = any).
     * @param provider optional rail filter ({@code null} = any).
     * @param from     optional inclusive lower bound on creation instant ({@code null} = open-ended).
     * @param to       optional inclusive upper bound on creation instant ({@code null} = open-ended).
     * @param pageable the bounded page (size capped upstream by {@code PageRequestFactory}).
     * @return the matching page of {@link AdminPaymentDto}.
     */
    Page<AdminPaymentDto> search(TopUpStatus status, MobileMoneyProvider provider, Instant from, Instant to,
                                 Pageable pageable);

    /**
     * Computes summary totals (counts + net settled/refunded money) over the same optional filter window.
     *
     * @param provider optional rail filter ({@code null} = any).
     * @param from     optional inclusive lower bound on creation instant ({@code null} = open-ended).
     * @param to       optional inclusive upper bound on creation instant ({@code null} = open-ended).
     * @return the window totals.
     */
    PaymentTotalsDto totals(MobileMoneyProvider provider, Instant from, Instant to);

    /**
     * Reads one top-up by public id for an operator (full admin view).
     *
     * @param publicId the top-up public id.
     * @return the {@link AdminPaymentDto}.
     * @throws com.taarifu.common.error.ResourceNotFoundException if no such top-up exists.
     */
    AdminPaymentDto get(UUID publicId);
}

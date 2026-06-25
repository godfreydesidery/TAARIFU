package com.taarifu.payments.application.service;

import com.taarifu.payments.api.PaymentQueryApi;
import com.taarifu.payments.api.dto.AdminPaymentDto;
import com.taarifu.payments.api.dto.PaymentTotalsDto;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.repository.TopUpRepository;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import com.taarifu.common.error.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side application service implementing the published {@link PaymentQueryApi} for ADMIN payment queries
 * (ADR-0015 addendum; ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: filtered/paged search over {@code top_up}, window totals (counts + net settled/refunded
 * money), and a single-top-up admin read — mapping entities to boundary DTOs so no entity leaks past the
 * module (CLAUDE.md §8). Read-only ({@code @Transactional(readOnly = true)}).</p>
 *
 * <p><b>Privacy (PRD §18):</b> the DTOs carry no MSISDN (never stored) and no PII; {@code buyerId} is an
 * opaque account UUID. <b>Fence (D18):</b> a read-only money view with no democratic-weight path and no
 * balance read.</p>
 */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService implements PaymentQueryApi {

    /**
     * Typed lower/upper sentinels used to coalesce an absent ({@code null}) time-window bound.
     *
     * <p>WHY: the repository filters use the {@code (:from is null or t.createdAt >= :from)} idiom. When a
     * temporal parameter is bound as {@code NULL}, PostgreSQL cannot infer the type of that bind in the
     * {@code IS NULL} position and fails the whole statement with "could not determine data type of
     * parameter $n" (the varchar status/provider binds survive this; only the timestamps break). Passing a
     * NON-null, typed bound makes the bind type-resolvable while preserving semantics — {@code createdAt} is
     * always within [EPOCH, year-9999], so an unfiltered window matches every row exactly as a null bound
     * would have.</p>
     */
    private static final Instant MIN_INSTANT = Instant.EPOCH;
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    private final TopUpRepository topUps;
    private final PaymentsGatewayProperties config;

    /**
     * @param topUps top-up persistence (filtered search + aggregate totals).
     * @param config bound gateway settings (the currency the totals are expressed in).
     */
    public PaymentQueryService(TopUpRepository topUps, PaymentsGatewayProperties config) {
        this.topUps = topUps;
        this.config = config;
    }

    /** {@inheritDoc} */
    @Override
    public Page<AdminPaymentDto> search(TopUpStatus status, MobileMoneyProvider provider, Instant from,
                                        Instant to, Pageable pageable) {
        return topUps.search(status, provider, lower(from), upper(to), pageable).map(AdminPaymentDto::from);
    }

    /** {@inheritDoc} */
    @Override
    public PaymentTotalsDto totals(MobileMoneyProvider provider, Instant from, Instant to) {
        Instant lo = lower(from);
        Instant hi = upper(to);
        long succeeded = topUps.countByStatus(TopUpStatus.SUCCEEDED, provider, lo, hi);
        long failed = topUps.countByStatus(TopUpStatus.FAILED, provider, lo, hi);
        long pending = topUps.countByStatus(TopUpStatus.PENDING, provider, lo, hi);
        long refunded = topUps.countByStatus(TopUpStatus.REFUNDED, provider, lo, hi);
        long settledMinor = topUps.sumAmountMinorByStatus(TopUpStatus.SUCCEEDED, provider, lo, hi);
        long refundedMinor = topUps.sumAmountMinorByStatus(TopUpStatus.REFUNDED, provider, lo, hi);
        return new PaymentTotalsDto(succeeded, failed, pending, refunded, settledMinor, refundedMinor,
                config.currency());
    }

    /** Coalesces an absent window start to a typed lower sentinel (see {@link #MIN_INSTANT}). */
    private static Instant lower(Instant from) {
        return from != null ? from : MIN_INSTANT;
    }

    /** Coalesces an absent window end to a typed upper sentinel (see {@link #MAX_INSTANT}). */
    private static Instant upper(Instant to) {
        return to != null ? to : MAX_INSTANT;
    }

    /** {@inheritDoc} */
    @Override
    public AdminPaymentDto get(UUID publicId) {
        return topUps.findByPublicId(publicId)
                .map(AdminPaymentDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("payments.topUp.notFound", publicId));
    }
}

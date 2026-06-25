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
        return topUps.search(status, provider, from, to, pageable).map(AdminPaymentDto::from);
    }

    /** {@inheritDoc} */
    @Override
    public PaymentTotalsDto totals(MobileMoneyProvider provider, Instant from, Instant to) {
        long succeeded = topUps.countByStatus(TopUpStatus.SUCCEEDED, provider, from, to);
        long failed = topUps.countByStatus(TopUpStatus.FAILED, provider, from, to);
        long pending = topUps.countByStatus(TopUpStatus.PENDING, provider, from, to);
        long refunded = topUps.countByStatus(TopUpStatus.REFUNDED, provider, from, to);
        long settledMinor = topUps.sumAmountMinorByStatus(TopUpStatus.SUCCEEDED, provider, from, to);
        long refundedMinor = topUps.sumAmountMinorByStatus(TopUpStatus.REFUNDED, provider, from, to);
        return new PaymentTotalsDto(succeeded, failed, pending, refunded, settledMinor, refundedMinor,
                config.currency());
    }

    /** {@inheritDoc} */
    @Override
    public AdminPaymentDto get(UUID publicId) {
        return topUps.findByPublicId(publicId)
                .map(AdminPaymentDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("payments.topUp.notFound", publicId));
    }
}

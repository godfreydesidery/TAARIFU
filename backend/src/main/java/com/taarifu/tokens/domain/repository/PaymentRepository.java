package com.taarifu.tokens.domain.repository;

import com.taarifu.tokens.domain.model.Payment;
import com.taarifu.tokens.domain.model.enums.PaymentProviderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Payment} — Phase 2 seam (ARCHITECTURE.md §3.3, PRD §23.6).
 *
 * <p>Responsibility: idempotency + reconciliation lookups for the (Phase 2) purchase flow. The provider-ref
 * finder lets webhook reconciliation match a settlement to exactly one payment without trusting the
 * callback body (PRD §23.5 anti-fraud). No purchase logic ships in MVP.</p>
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * @param idempotencyKey the initiation dedup key.
     * @return the existing payment, or empty if this initiation is new.
     */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * @param provider    settlement rail.
     * @param providerRef provider settlement reference.
     * @return the matching payment, or empty.
     */
    Optional<Payment> findByProviderAndProviderRef(PaymentProviderType provider, String providerRef);

    /**
     * @param publicId a payment's public id.
     * @return the payment, or empty.
     */
    Optional<Payment> findByPublicId(UUID publicId);
}

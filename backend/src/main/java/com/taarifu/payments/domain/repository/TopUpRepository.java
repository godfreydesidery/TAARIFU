package com.taarifu.payments.domain.repository;

import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link TopUp} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the lookups the top-up flow needs — by public id (status endpoint), by idempotency key
 * (initiate dedup), and by {@code (provider, provider_ref)} (webhook reconciliation), plus a
 * pessimistically-locked load by provider reference used by the reconciliation path. Soft-deleted rows are
 * excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface TopUpRepository extends JpaRepository<TopUp, Long> {

    /**
     * @param publicId the top-up's public id.
     * @return the top-up, or empty if none/soft-deleted.
     */
    Optional<TopUp> findByPublicId(UUID publicId);

    /**
     * @param idempotencyKey the initiation dedup key.
     * @return the existing top-up for this key, or empty (idempotent initiate, PRD §23.5).
     */
    Optional<TopUp> findByIdempotencyKey(String idempotencyKey);

    /**
     * @param provider    the settling rail.
     * @param providerRef the provider settlement reference.
     * @return the one matching top-up, or empty (the partial-unique reconciliation anchor).
     */
    Optional<TopUp> findByProviderAndProviderRef(MobileMoneyProvider provider, String providerRef);

    /**
     * Loads the top-up matching {@code (provider, providerRef)} with a row-level write lock for the duration
     * of the transaction.
     *
     * <p>WHY a pessimistic write lock on the reconciliation path: a provider may deliver the same callback
     * concurrently (two aggregator workers, a retry overlapping the original). Serialising on the row makes
     * the SUCCEEDED transition + wallet credit happen once even under concurrent delivery; the unique
     * constraints stop <i>replays</i>, the lock stops a <i>concurrent</i> double-credit (PRD §23.5
     * race-safe). The {@code @Version} optimistic lock on {@link TopUp} is the second line of defence.</p>
     *
     * @param provider    the settling rail.
     * @param providerRef the provider settlement reference.
     * @return the locked top-up, or empty if no row matches the reference yet.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TopUp t where t.provider = :provider and t.providerRef = :providerRef")
    Optional<TopUp> findForUpdateByProviderRef(@Param("provider") MobileMoneyProvider provider,
                                               @Param("providerRef") String providerRef);
}

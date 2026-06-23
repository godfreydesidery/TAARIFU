package com.taarifu.tokens.domain.repository;

import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Wallet} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: lookup of a wallet by owner (the one-wallet-per-owner key) and by public id, plus a
 * pessimistically-locked load used by the spend/grant path. Soft-deleted rows are excluded by the entity's
 * {@code @SQLRestriction}.</p>
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * @param publicId the wallet's public id.
     * @return the wallet, or empty if none/soft-deleted.
     */
    Optional<Wallet> findByPublicId(UUID publicId);

    /**
     * @param ownerType owner class.
     * @param ownerId   owner public id.
     * @return the owner's wallet, or empty if not yet provisioned.
     */
    Optional<Wallet> findByOwnerTypeAndOwnerId(WalletOwnerType ownerType, UUID ownerId);

    /**
     * Loads a wallet for an owner with a row-level write lock for the duration of the transaction.
     *
     * <p>WHY a pessimistic write lock on the spend/grant path: it serialises concurrent balance mutations
     * on the same wallet so two simultaneous spends cannot both read the same balance and over-spend. This
     * pairs with the unique idempotency key (which stops <i>replays</i>); the lock stops <i>concurrent
     * distinct</i> spends from racing (PRD §23.5 — race-safe, ledger-driven). The {@code @Version} optimistic
     * lock on {@link Wallet} is the second line of defence.</p>
     *
     * @param ownerType owner class.
     * @param ownerId   owner public id.
     * @return the locked wallet, or empty if not provisioned.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.ownerType = :ownerType and w.ownerId = :ownerId")
    Optional<Wallet> findForUpdate(@Param("ownerType") WalletOwnerType ownerType,
                                   @Param("ownerId") UUID ownerId);

    /**
     * @param ownerType owner class.
     * @param ownerId   owner public id.
     * @return {@code true} if a wallet already exists for the owner.
     */
    boolean existsByOwnerTypeAndOwnerId(WalletOwnerType ownerType, UUID ownerId);
}
